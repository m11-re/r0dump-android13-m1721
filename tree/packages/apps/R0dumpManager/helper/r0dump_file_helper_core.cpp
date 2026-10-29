#include "r0dump_file_helper_core.h"

#include <errno.h>
#include <fcntl.h>
#include <sys/fsuid.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

#include <array>
#include <string>

namespace r0dump::file_helper {
namespace {

constexpr mode_t kDirectoryMode = 02770;

// The helper creates the run directory under the target app's external files tree and then verifies
// nothing else can reach it.  Upstream targets Android 16, where that tree is FUSE-backed and honours
// chown()/chmod() exactly, so it can demand the exact (uid, gid, 02770) triple.
//
// This device keeps Android/data on a separate sdcardfs mount (see /proc/mounts).  sdcardfs does not
// implement chown()/chmod(): it rewrites ownership and mode from its mount options
// (fsuid=1023, fsgid=1023, mask=6, derive_gid), so a directory created there always reads back as the
// calling uid, gid 1015 (sdcard_rw) and mode 0771 regardless of what the helper requested.  Demanding
// the FUSE triple therefore always fails here.
//
// This accepts the exact FUSE contract, or the sdcardfs shape -- but the sdcardfs shape must still
// prove the directory is private to the requesting app: its owner must be the app's own uid and no
// "other" permission bit may be set.  A directory owned by anyone else, or world-accessible, is
// still rejected.

// gid sdcardfs reports for directories below Android/data.
constexpr gid_t kSdcardfsGroup = 1015;  // AID_SDCARD_RW

bool DirectoryMatchesContract(const struct stat &st, uid_t shared_uid,
                               uid_t process_uid, gid_t gid) {
  if (!S_ISDIR(st.st_mode)) {
    return false;
  }
  // FUSE: the helper's chown/chmod are honoured, so the directory carries the shared data
  // ownership and the exact mode it asked for.
  if (st.st_uid == shared_uid && st.st_gid == gid &&
      (st.st_mode & 02777) == kDirectoryMode) {
    return true;
  }
  // sdcardfs: chown/chmod are ignored and the mode is pinned to 0771, and directories created
  // below a package's external files tree are reported as owned by that package's uid.  Requiring
  // the app to own its own capture directory is the correct outcome here anyway -- the ART engine
  // writes the artifacts from inside the target process -- so accept that shape as long as nothing
  // else can read or write it.
  //
  // Other-execute cannot be cleared (sdcardfs ignores chmod), and is harmless: search alone does not
  // disclose contents, and o+r / o+w are still rejected.
  constexpr mode_t kSdcardfsAllowedOtherBits = S_IXOTH;
  return st.st_uid == process_uid && st.st_gid == kSdcardfsGroup &&
         (st.st_mode & (S_IRWXO & ~kSdcardfsAllowedOtherBits)) == 0;
}

class ScopedFd final {
public:
  explicit ScopedFd(int fd = -1) : fd_(fd) {}
  ~ScopedFd() {
    if (fd_ >= 0)
      close(fd_);
  }
  ScopedFd(const ScopedFd &) = delete;
  ScopedFd &operator=(const ScopedFd &) = delete;
  ScopedFd(ScopedFd &&other) noexcept : fd_(other.release()) {}
  ScopedFd &operator=(ScopedFd &&other) noexcept {
    if (this != &other) {
      if (fd_ >= 0)
        close(fd_);
      fd_ = other.release();
    }
    return *this;
  }
  int get() const { return fd_; }
  int release() {
    int result = fd_;
    fd_ = -1;
    return result;
  }

private:
  int fd_;
};

bool IsAsciiAlpha(char c) {
  return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

bool IsAsciiDigit(char c) { return c >= '0' && c <= '9'; }

Status StatusForErrno(int error) {
  if (error == EACCES || error == EPERM)
    return Status::kPermissionDenied;
  if (error == ENOENT || error == ENOTDIR || error == ENODEV) {
    return Status::kStorageUnavailable;
  }
  if (error == EEXIST)
    return Status::kExists;
  return Status::kInternalError;
}

// Runs `fn` with the filesystem uid switched to `access_uid`, restoring it afterwards.
//
// Every operation performed below Android/data must go through this.  sdcardfs derives a per-package
// gid and treats the package's own uid as the owner, so the service identity (root, gid ext_data_rw)
// is refused with EACCES even for operations root would normally be allowed.  Measured on device:
// opening a package directory as the service identity fails, the same open with fsuid switched to
// the app uid succeeds, and mkdirat performed after the fsuid was restored fails again.
//
// Returns the value returned by `fn`, and sets `errno` to the error `fn` observed.
template <typename Fn>
static int RunAsUid(uid_t access_uid, Fn &&fn) {
  const uid_t original_fsuid = static_cast<uid_t>(setfsuid(access_uid));
  const bool switched =
      static_cast<uid_t>(setfsuid(static_cast<uid_t>(-1))) == access_uid;
  if (!switched) {
    setfsuid(original_fsuid);
    errno = EACCES;
    return -1;
  }
  const int result = fn();
  const int saved_errno = errno;
  setfsuid(original_fsuid);
  if (static_cast<uid_t>(setfsuid(static_cast<uid_t>(-1))) != original_fsuid) {
    errno = EPERM;
    return -1;
  }
  errno = saved_errno;
  return result;
}

ScopedFd OpenDirectoryAt(int parent_fd, std::string_view name) {
  std::string owned(name);
  return ScopedFd(openat(parent_fd, owned.c_str(),
                         O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
}

ScopedFd OpenDirectoryPathAt(int parent_fd, std::string_view name) {
  std::string owned(name);
  return ScopedFd(openat(parent_fd, owned.c_str(),
                         O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
}

ScopedFd OpenDirectoryAtAsUid(int parent_fd, std::string_view name,
                              uid_t access_uid) {
  ScopedFd opened = OpenDirectoryAt(parent_fd, name);
  if (opened.get() >= 0 || (errno != EACCES && errno != EPERM) ||
      access_uid == geteuid()) {
    return opened;
  }

  const uid_t original_fsuid = static_cast<uid_t>(setfsuid(access_uid));
  if (static_cast<uid_t>(setfsuid(static_cast<uid_t>(-1))) != access_uid) {
    setfsuid(original_fsuid);
    errno = EACCES;
    return ScopedFd();
  }
  opened = OpenDirectoryAt(parent_fd, name);
  const int saved_errno = errno;
  setfsuid(original_fsuid);
  if (static_cast<uid_t>(setfsuid(static_cast<uid_t>(-1))) !=
      original_fsuid) {
    errno = EPERM;
    return ScopedFd();
  }
  errno = saved_errno;
  return opened;
}

Status OpenExistingAt(int parent_fd, std::string_view name, uid_t access_uid,
                       ScopedFd *out) {
  // sdcardfs gives every package below Android/data a gid derived from that package's uid, so a
  // root helper holding only the sdcard group is refused with EACCES while walking into a foreign
  // package directory -- even though the directory itself is 0771 and the helper is root.  The
  // fix is to perform the lookup as the package's own uid; the helper already owns the necessary
  // capability (SETUID / setfsuid), and this is exactly what EnsureDirectoryAt does below.
  //
  // Measured on device: opening "com.job.android" as the service identity fails with EACCES while
  // the same open with fsuid switched to the directory owner succeeds.
  ScopedFd opened = OpenDirectoryAtAsUid(parent_fd, name, access_uid);
  if (opened.get() < 0) {
    return StatusForErrno(errno);
  }
  *out = std::move(opened);
  return Status::kOk;
}

Status EnsureDirectoryAt(int parent_fd, std::string_view name, uid_t uid,
                         gid_t gid, uid_t access_uid, ScopedFd *out) {
  std::string owned(name);
  const int created = RunAsUid(access_uid, [&]() {
    return mkdirat(parent_fd, owned.c_str(), kDirectoryMode);
  });
  if (created != 0 && errno != EEXIST) {
    return StatusForErrno(errno);
  }
  ScopedFd opened = OpenDirectoryAtAsUid(parent_fd, name, access_uid);
  if (opened.get() < 0)
    return StatusForErrno(errno);
  if (fchown(opened.get(), uid, gid) != 0 ||
      fchmod(opened.get(), kDirectoryMode) != 0) {
    return StatusForErrno(errno);
  }
  struct stat st{};
  if (fstat(opened.get(), &st) != 0 ||
      !DirectoryMatchesContract(st, uid, access_uid, gid)) {
    return Status::kPermissionDenied;
  }
  *out = std::move(opened);
  return Status::kOk;
}

Status CreateOrVerifyTrustedDirectoryAt(int parent_fd, std::string_view name,
                                        uid_t uid, uid_t process_uid, gid_t gid,
                                        ScopedFd *out) {
  std::string owned(name);
  bool created = false;
  const int created_result = RunAsUid(uid, [&]() {
    return mkdirat(parent_fd, owned.c_str(), kDirectoryMode);
  });
  if (created_result == 0) {
    created = true;
  } else if (errno != EEXIST) {
    return StatusForErrno(errno);
  }

  ScopedFd opened = OpenDirectoryAtAsUid(parent_fd, name, process_uid);
  if (opened.get() < 0) {
    return StatusForErrno(errno);
  }
  if (created && (fchown(opened.get(), uid, gid) != 0 ||
                  fchmod(opened.get(), kDirectoryMode) != 0)) {
    const int saved_errno = errno;
    opened = ScopedFd();
    unlinkat(parent_fd, owned.c_str(), AT_REMOVEDIR);
    return StatusForErrno(saved_errno);
  }

  struct stat st{};
  if (fstat(opened.get(), &st) != 0 || !DirectoryMatchesContract(st, uid, process_uid, gid)) {
    if (created) {
      opened = ScopedFd();
      unlinkat(parent_fd, owned.c_str(), AT_REMOVEDIR);
    }
    return Status::kPermissionDenied;
  }
  *out = std::move(opened);
  return Status::kOk;
}

Status CreateExclusiveDirectoryAt(int parent_fd, std::string_view name, uid_t uid,
                                  uid_t process_uid, gid_t gid, ScopedFd *out) {
  std::string owned(name);
  if (RunAsUid(process_uid, [&]() {
        return mkdirat(parent_fd, owned.c_str(), kDirectoryMode);
      }) != 0) {
    return StatusForErrno(errno);
  }
  ScopedFd opened = OpenDirectoryAtAsUid(parent_fd, name, process_uid);
  if (opened.get() < 0) {
    unlinkat(parent_fd, owned.c_str(), AT_REMOVEDIR);
    return StatusForErrno(errno);
  }
  if (fchown(opened.get(), uid, gid) != 0 ||
      fchmod(opened.get(), kDirectoryMode) != 0) {
    const int saved_errno = errno;
    opened = ScopedFd();
    unlinkat(parent_fd, owned.c_str(), AT_REMOVEDIR);
    return StatusForErrno(saved_errno);
  }
  struct stat st{};
  if (fstat(opened.get(), &st) != 0 || !DirectoryMatchesContract(st, uid, process_uid, gid)) {
    opened = ScopedFd();
    unlinkat(parent_fd, owned.c_str(), AT_REMOVEDIR);
    return Status::kPermissionDenied;
  }
  *out = std::move(opened);
  return Status::kOk;
}

void RemovePreparedBranch(int process_fd, int run_fd,
                          std::string_view process_name) {
  static constexpr std::array<std::string_view, 4> kFixedDirectories = {
      "metadata", "artifacts", "records", "methods"};
  for (std::string_view directory : kFixedDirectories) {
    std::string owned(directory);
    unlinkat(process_fd, owned.c_str(), AT_REMOVEDIR);
  }
  std::string owned(process_name);
  unlinkat(run_fd, owned.c_str(), AT_REMOVEDIR);
}

} // namespace

bool IsValidPackageName(std::string_view value) {
  if (value.empty() || value.size() >= kPackageBytes || value.front() == '.' ||
      value.back() == '.') {
    return false;
  }
  bool has_dot = false;
  for (char c : value) {
    if (c == '.') {
      has_dot = true;
    } else if (!IsAsciiAlpha(c) && !IsAsciiDigit(c) && c != '_') {
      return false;
    }
  }
  return has_dot;
}

bool IsValidProcessName(std::string_view value) {
  if (value.empty() || value.size() >= kProcessBytes)
    return false;
  for (char c : value) {
    if (!IsAsciiAlpha(c) && !IsAsciiDigit(c) && c != '_' && c != '.' &&
        c != ':')
      return false;
  }
  return true;
}

bool IsValidRunId(std::string_view value) {
  if (value.empty() || value.size() >= kRunIdBytes || value == "." ||
      value == "..")
    return false;
  for (char c : value) {
    if (!IsAsciiAlpha(c) && !IsAsciiDigit(c) && c != '_' && c != '.' &&
        c != '-')
      return false;
  }
  return true;
}

bool ReadFixedString(const char *value, size_t capacity,
                     std::string_view *out) {
  if (value == nullptr || out == nullptr || capacity == 0u)
    return false;
  size_t length = 0u;
  while (length < capacity && value[length] != '\0')
    ++length;
  if (length == 0u || length == capacity)
    return false;
  for (size_t i = length + 1u; i < capacity; ++i) {
    if (value[i] != '\0')
      return false;
  }
  *out = std::string_view(value, length);
  return true;
}

Status PrepareDirectoryTreeAt(int root_fd, const Request &request,
                              const Ownership &ownership, int *out_process_fd) {
  if (root_fd < 0 || out_process_fd == nullptr || request.user_id != 0u ||
      request.target_uid == 0u || request.target_pid == 0u) {
    return Status::kProtocolError;
  }
  *out_process_fd = -1;
  std::string_view package_name;
  std::string_view process_name;
  std::string_view run_id;
  if (!ReadFixedString(request.package_name, sizeof(request.package_name),
                       &package_name) ||
      !ReadFixedString(request.process_name, sizeof(request.process_name),
                       &process_name) ||
      !ReadFixedString(request.run_id, sizeof(request.run_id), &run_id) ||
      !IsValidPackageName(package_name) || !IsValidProcessName(process_name) ||
      !IsValidRunId(run_id)) {
    return Status::kProtocolError;
  }

  ScopedFd android_directory;
  ScopedFd data_directory;
  ScopedFd package_directory;
  ScopedFd files_directory;
  // "Android" and "data" are shared, but the package directory is per-app isolated; every step is
  // performed as the target uid so the walk works under sdcardfs as well as FUSE.
  Status status = OpenExistingAt(root_fd, "Android", ownership.process_uid,
                                 &android_directory);
  if (status != Status::kOk)
    return status;
  status = OpenExistingAt(android_directory.get(), "data", ownership.process_uid,
                          &data_directory);
  if (status != Status::kOk)
    return status;
  status = OpenExistingAt(data_directory.get(), package_name,
                          ownership.process_uid, &package_directory);
  if (status != Status::kOk)
    return status;
  status = OpenExistingAt(package_directory.get(), "files",
                          ownership.process_uid, &files_directory);
  if (status != Status::kOk)
    return status;

  ScopedFd fusion_directory;
  ScopedFd runs_directory;
  ScopedFd run_directory;
  status =
      EnsureDirectoryAt(files_directory.get(), "r0dump-fusion",
                        ownership.shared_uid, ownership.gid,
                        ownership.process_uid, &fusion_directory);
  if (status != Status::kOk)
    return status;
  status =
      EnsureDirectoryAt(fusion_directory.get(), "runs", ownership.shared_uid,
                        ownership.gid, ownership.process_uid, &runs_directory);
  if (status != Status::kOk)
    return status;
  status = CreateOrVerifyTrustedDirectoryAt(
      runs_directory.get(), run_id, ownership.shared_uid, ownership.process_uid,
      ownership.gid, &run_directory);
  if (status != Status::kOk)
    return status;

  char process_directory_name[32];
  const int length =
      snprintf(process_directory_name, sizeof(process_directory_name),
               "process-%u", request.target_pid);
  if (length <= 0 ||
      static_cast<size_t>(length) >= sizeof(process_directory_name)) {
    return Status::kProtocolError;
  }
  ScopedFd process_directory;
  status = CreateExclusiveDirectoryAt(
      run_directory.get(), process_directory_name, ownership.process_uid,
      ownership.process_uid, ownership.gid, &process_directory);
  if (status != Status::kOk)
    return status;

  static constexpr std::array<std::string_view, 4> kFixedDirectories = {
      "metadata", "artifacts", "records", "methods"};
  for (std::string_view directory : kFixedDirectories) {
    ScopedFd ignored;
    status = CreateExclusiveDirectoryAt(process_directory.get(), directory,
                                        ownership.process_uid, ownership.process_uid,
                                        ownership.gid, &ignored);
    if (status != Status::kOk) {
      RemovePreparedBranch(process_directory.get(), run_directory.get(),
                           process_directory_name);
      return status;
    }
  }
  // The O_PATH fd handed to system_server is opened as the target uid for the same reason as the
  // rest of the walk: the directory belongs to the app under sdcardfs.
  const uid_t transfer_fsuid = static_cast<uid_t>(setfsuid(ownership.process_uid));
  ScopedFd transfer_directory =
      OpenDirectoryPathAt(run_directory.get(), process_directory_name);
  const int transfer_errno = errno;
  setfsuid(transfer_fsuid);
  if (transfer_directory.get() < 0) {
    errno = transfer_errno;
    return StatusForErrno(errno);
  }
  struct stat writable_stat{};
  struct stat transfer_stat{};
  if (fstat(process_directory.get(), &writable_stat) != 0 ||
      fstat(transfer_directory.get(), &transfer_stat) != 0 ||
      writable_stat.st_dev != transfer_stat.st_dev ||
      writable_stat.st_ino != transfer_stat.st_ino) {
    return Status::kPermissionDenied;
  }
  *out_process_fd = transfer_directory.release();
  return Status::kOk;
}

} // namespace r0dump::file_helper
