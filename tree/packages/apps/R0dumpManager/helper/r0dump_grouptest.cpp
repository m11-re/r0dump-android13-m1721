// Diagnostic 5: replicate the helper's credentials (uid 0, gid 1078, groups 1023/9997) and see
// whether mkdirat below an app's files/ directory succeeds with fsuid restored to 0.
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stdio.h>
#include <string.h>
#include <sys/fsuid.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static const char *kRoot = "/mnt/user/0/emulated/0";

static int openAsUid(int parent, const char *name, uid_t uid) {
  int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (fd >= 0) return fd;
  uid_t orig = setfsuid(uid);
  fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  int e = errno;
  setfsuid(orig);
  errno = e;
  return fd;
}

int main() {
  printf("start: uid=%u gid=%u euid=%u egid=%u\n", (unsigned)getuid(), (unsigned)getgid(),
         (unsigned)geteuid(), (unsigned)getegid());
  // Replicate the service definition: user root, group ext_data_rw media_rw sdcard_rw everybody
  gid_t groups[] = {1078, 1023, 1015, 9997};
  if (setgroups(4, groups) != 0) printf("setgroups: %s\n", strerror(errno));
  if (setgid(1078) != 0) printf("setgid: %s\n", strerror(errno));
  printf("after:  uid=%u gid=%u groups ok\n", (unsigned)getuid(), (unsigned)getgid());

  int root = open(kRoot, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  int a  = openAsUid(root, "Android", 10169);
  int d  = openAsUid(a, "data", 10169);
  int pk = openAsUid(d, "com.job.android", 10169);
  int fl = openAsUid(pk, "files", 10169);
  printf("fds: Android=%d data=%d pkg=%d files=%d fsuid=%u\n", a, d, pk, fl,
         (unsigned)setfsuid((uid_t)-1));
  if (fl < 0) { printf("walk failed: %s\n", strerror(errno)); return 1; }
  if (mkdirat(fl, "r0z", 02770) == 0) { printf("mkdirat as-is (fsuid 0): OK\n"); unlinkat(fl, "r0z", AT_REMOVEDIR); }
  else printf("mkdirat as-is (fsuid 0): FAIL %s\n", strerror(errno));
  uid_t orig = setfsuid(10169);
  if (mkdirat(fl, "r0w", 02770) == 0) { printf("mkdirat with fsuid=10169: OK\n"); unlinkat(fl, "r0w", AT_REMOVEDIR); }
  else printf("mkdirat with fsuid=10169: FAIL %s\n", strerror(errno));
  setfsuid(orig);
  return 0;
}
