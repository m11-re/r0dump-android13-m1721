#ifndef R0DUMP_FILE_HELPER_CORE_H_
#define R0DUMP_FILE_HELPER_CORE_H_

#include <sys/types.h>

#include <string_view>

#include "r0dump_file_helper_protocol.h"

namespace r0dump::file_helper {

struct Ownership {
  uid_t shared_uid;
  uid_t process_uid;
  gid_t gid;
};

bool IsValidPackageName(std::string_view value);
bool IsValidProcessName(std::string_view value);
bool IsValidRunId(std::string_view value);
bool ReadFixedString(const char *value, size_t capacity, std::string_view *out);

// root_fd must refer to /mnt/user/0/emulated/0 in production. On success,
// returns an O_PATH|O_DIRECTORY|O_CLOEXEC descriptor for the new
// process-<pid> directory in out_process_fd.
Status PrepareDirectoryTreeAt(int root_fd, const Request &request,
                              const Ownership &ownership, int *out_process_fd);

} // namespace r0dump::file_helper

#endif // R0DUMP_FILE_HELPER_CORE_H_
