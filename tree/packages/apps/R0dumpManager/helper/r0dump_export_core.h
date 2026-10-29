#ifndef R0DUMP_EXPORT_CORE_H_
#define R0DUMP_EXPORT_CORE_H_

#include <stdint.h>

#include <filesystem>
#include <string>
#include <vector>

#include "r0dump_file_helper_protocol.h"

namespace r0dump::file_helper {

struct ExportSummary {
  uint32_t remote_artifact_count = 0u;
  uint32_t record_count = 0u;
  uint32_t verified = 0u;
  uint32_t repaired = 0u;
  uint32_t partial = 0u;
  uint32_t usable_outputs = 0u;
  uint32_t codeitems_backfilled = 0u;
  std::string run_id;
  std::string archive_path;
  std::string sha256;
  std::string detail;
};

// Repairs the newest run below storage_root/Android/data/<package>/files and
// writes the final archive below downloads_root/r0dump-exports/<package>.
// apk_fds must contain base.apk first, followed by optional split APKs.
Status ExportLatest(const std::filesystem::path &storage_root,
                    const std::filesystem::path &downloads_root,
                    std::string_view package_name,
                    const std::vector<int> &apk_fds, ExportSummary *summary);

} // namespace r0dump::file_helper

#endif // R0DUMP_EXPORT_CORE_H_
