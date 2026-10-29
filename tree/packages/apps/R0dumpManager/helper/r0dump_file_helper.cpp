#include <android-base/parseint.h>
#include <android-base/properties.h>
#include <cutils/sockets.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <log/log.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <private/android_filesystem_config.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include "r0dump_export_core.h"
#include "r0dump_file_helper_core.h"

namespace r0dump::file_helper {
namespace {

constexpr char kStorageRoot[] = "/mnt/user/0/emulated/0";
constexpr char kDownloadsRoot[] = "/mnt/user/0/emulated/0/Downloads";
constexpr char kPropertyPrefix[] = "persist.sys.r0dump.fusion.";
constexpr size_t kMaxApkCount = 32u;

std::atomic<bool> g_export_in_progress(false);

std::string Property(std::string_view suffix) {
  return android::base::GetProperty(
      std::string(kPropertyPrefix) + std::string(suffix), "");
}

Status ValidateStableConfig(const Request &request) {
  if ((request.generation & 1u) != 0u || Property("enabled") != "1")
    return Status::kDisabled;
  uint64_t generation_before = 0u;
  if (!android::base::ParseUint(Property("generation"), &generation_before) ||
      generation_before != request.generation) {
    return Status::kConfigMismatch;
  }
  std::string_view package_name;
  std::string_view process_name;
  std::string_view run_id;
  if (!ReadFixedString(request.package_name, sizeof(request.package_name),
                       &package_name) ||
      !ReadFixedString(request.process_name, sizeof(request.process_name),
                       &process_name) ||
      !ReadFixedString(request.run_id, sizeof(request.run_id), &run_id)) {
    return Status::kProtocolError;
  }
  const std::string configured_process = Property("target_process");
  if (Property("target_package") != package_name ||
      Property("run_id") != run_id ||
      (!configured_process.empty() && configured_process != process_name)) {
    return Status::kConfigMismatch;
  }
  uint64_t generation_after = 0u;
  if (!android::base::ParseUint(Property("generation"), &generation_after) ||
      generation_after != generation_before || (generation_after & 1u) != 0u) {
    return Status::kConfigMismatch;
  }
  return Status::kOk;
}

bool SendResponse(int client_fd, Status status, int process_fd) {
  const Response response = {
      .magic = kProtocolMagic,
      .version = kProtocolVersion,
      .size = static_cast<uint16_t>(sizeof(Response)),
      .status = static_cast<int32_t>(status),
  };
  iovec io = {.iov_base = const_cast<Response *>(&response),
              .iov_len = sizeof(response)};
  msghdr message = {};
  message.msg_iov = &io;
  message.msg_iovlen = 1;
  alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))] = {};
  if (status == Status::kOk && process_fd >= 0) {
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &process_fd, sizeof(process_fd));
  }
  return TEMP_FAILURE_RETRY(sendmsg(client_fd, &message, MSG_NOSIGNAL)) ==
         static_cast<ssize_t>(sizeof(response));
}

template <size_t N>
void CopyResponseString(char (&destination)[N], std::string_view value) {
  const size_t count = std::min(value.size(), N - 1u);
  memcpy(destination, value.data(), count);
  destination[count] = '\0';
}

bool SendExportResponse(int client_fd, Status status,
                        const ExportSummary &summary) {
  ExportResponse response = {};
  response.magic = kExportResponseMagic;
  response.version = kExportProtocolVersion;
  response.size = static_cast<uint16_t>(sizeof(response));
  response.status = static_cast<int32_t>(status);
  response.remote_artifact_count = summary.remote_artifact_count;
  response.record_count = summary.record_count;
  response.verified = summary.verified;
  response.repaired = summary.repaired;
  response.partial = summary.partial;
  response.usable_outputs = summary.usable_outputs;
  response.codeitems_backfilled = summary.codeitems_backfilled;
  CopyResponseString(response.run_id, summary.run_id);
  CopyResponseString(response.archive_path, summary.archive_path);
  CopyResponseString(response.sha256, summary.sha256);
  CopyResponseString(response.detail, summary.detail);
  return TEMP_FAILURE_RETRY(
             send(client_fd, &response, sizeof(response), MSG_NOSIGNAL)) ==
         static_cast<ssize_t>(sizeof(response));
}

void CloseFds(const std::vector<int> &fds) {
  for (int fd : fds) {
    if (fd >= 0)
      close(fd);
  }
}

std::vector<int> ReceiveFds(const msghdr &message) {
  std::vector<int> fds;
  for (const cmsghdr *header = CMSG_FIRSTHDR(&message); header != nullptr;
       header = CMSG_NXTHDR(const_cast<msghdr *>(&message),
                            const_cast<cmsghdr *>(header))) {
    if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len < CMSG_LEN(sizeof(int))) {
      continue;
    }
    const size_t bytes = header->cmsg_len - CMSG_LEN(0);
    const size_t count = bytes / sizeof(int);
    const int *received =
        reinterpret_cast<const int *>(CMSG_DATA(const_cast<cmsghdr *>(header)));
    fds.insert(fds.end(), received, received + count);
  }
  return fds;
}

void RunExport(int client_fd, ExportRequest request, std::vector<int> apk_fds) {
  ExportSummary summary;
  std::string_view package_name;
  Status status = Status::kProtocolError;
  if (ReadFixedString(request.package_name, sizeof(request.package_name),
                      &package_name) &&
      IsValidPackageName(package_name)) {
    status = ExportLatest(kStorageRoot, kDownloadsRoot, package_name, apk_fds,
                          &summary);
  } else {
    summary.detail = "invalid_package";
  }
  if (status != Status::kOk) {
    ALOGW("r0dump export failed: status=%d package=%s detail=%s",
          static_cast<int>(status), request.package_name,
          summary.detail.c_str());
  }
  SendExportResponse(client_fd, status, summary);
  CloseFds(apk_fds);
  close(client_fd);
  g_export_in_progress.store(false);
}

// Returns true when the accept loop still owns client_fd.
bool HandleClient(int client_fd) {
  ucred peer = {};
  socklen_t peer_size = sizeof(peer);
  if (getsockopt(client_fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) != 0 ||
      peer_size != sizeof(peer) || peer.uid != AID_SYSTEM) {
    SendResponse(client_fd, Status::kInvalidCaller, -1);
    return true;
  }

  std::array<uint8_t, sizeof(Request)> request_bytes{};
  alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * kMaxApkCount)] = {};
  iovec io = {.iov_base = request_bytes.data(),
              .iov_len = request_bytes.size()};
  msghdr message = {};
  message.msg_iov = &io;
  message.msg_iovlen = 1;
  message.msg_control = control;
  message.msg_controllen = sizeof(control);
  const ssize_t received = TEMP_FAILURE_RETRY(
      recvmsg(client_fd, &message, MSG_TRUNC | MSG_CMSG_CLOEXEC));
  std::vector<int> received_fds = ReceiveFds(message);
  if (received < static_cast<ssize_t>(sizeof(uint32_t))) {
    CloseFds(received_fds);
    SendResponse(client_fd, Status::kProtocolError, -1);
    return true;
  }

  uint32_t magic = 0u;
  memcpy(&magic, request_bytes.data(), sizeof(magic));
  if (magic == kProtocolMagic) {
    if (!received_fds.empty() ||
        received != static_cast<ssize_t>(sizeof(Request))) {
      CloseFds(received_fds);
      SendResponse(client_fd, Status::kProtocolError, -1);
      return true;
    }
    Request request = {};
    memcpy(&request, request_bytes.data(), sizeof(request));
    if (request.version != kProtocolVersion ||
        request.size != sizeof(Request)) {
      SendResponse(client_fd, Status::kProtocolError, -1);
      return true;
    }
    Status status = ValidateStableConfig(request);
    int process_fd = -1;
    if (status == Status::kOk) {
      const int root_fd =
          open(kStorageRoot, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
      if (root_fd < 0) {
        ALOGW("r0dump helper could not open storage root %s: %s", kStorageRoot,
              strerror(errno));
        status = Status::kStorageUnavailable;
      } else {
        status = PrepareDirectoryTreeAt(
            root_fd, request, {AID_ROOT, request.target_uid, AID_EXT_DATA_RW},
            &process_fd);
        close(root_fd);
      }
    }
    if (status != Status::kOk) {
      ALOGW("r0dump helper rejected request: status=%d package=%s process=%s "
            "run_id=%s uid=%u pid=%u generation=%" PRIu64,
            static_cast<int>(status), request.package_name,
            request.process_name, request.run_id, request.target_uid,
            request.target_pid, request.generation);
    }
    SendResponse(client_fd, status, process_fd);
    if (process_fd >= 0)
      close(process_fd);
    return true;
  }

  if (magic == kExportProtocolMagic) {
    if (received != static_cast<ssize_t>(sizeof(ExportRequest))) {
      CloseFds(received_fds);
      ExportSummary summary;
      summary.detail = "invalid_export_request_size";
      SendExportResponse(client_fd, Status::kProtocolError, summary);
      return true;
    }
    ExportRequest request = {};
    memcpy(&request, request_bytes.data(), sizeof(request));
    std::string_view package_name;
    if (request.version != kExportProtocolVersion ||
        request.size != sizeof(ExportRequest) || request.user_id != 0u ||
        request.requester_uid == 0u || request.apk_count == 0u ||
        request.apk_count > kMaxApkCount ||
        received_fds.size() != request.apk_count ||
        !ReadFixedString(request.package_name, sizeof(request.package_name),
                         &package_name) ||
        !IsValidPackageName(package_name)) {
      CloseFds(received_fds);
      ExportSummary summary;
      summary.detail = "invalid_export_request";
      SendExportResponse(client_fd, Status::kProtocolError, summary);
      return true;
    }
    if (g_export_in_progress.exchange(true)) {
      CloseFds(received_fds);
      ExportSummary summary;
      summary.detail = "export_busy";
      SendExportResponse(client_fd, Status::kBusy, summary);
      return true;
    }
    std::thread(RunExport, client_fd, request, std::move(received_fds))
        .detach();
    return false;
  }

  CloseFds(received_fds);
  SendResponse(client_fd, Status::kProtocolError, -1);
  return true;
}

} // namespace
} // namespace r0dump::file_helper

int main() {
  using namespace r0dump::file_helper;
  const int server_fd = android_get_control_socket("r0dump_file_helper");
  if (server_fd < 0 || listen(server_fd, 8) != 0) {
    ALOGE("r0dump helper failed to acquire init socket: %s", strerror(errno));
    return 1;
  }
  for (;;) {
    const int client_fd =
        TEMP_FAILURE_RETRY(accept4(server_fd, nullptr, nullptr, SOCK_CLOEXEC));
    if (client_fd < 0) {
      ALOGE("r0dump helper accept failed: %s", strerror(errno));
      continue;
    }
    if (HandleClient(client_fd)) {
      close(client_fd);
    }
  }
}
