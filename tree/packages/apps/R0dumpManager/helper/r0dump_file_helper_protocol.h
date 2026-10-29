#ifndef R0DUMP_FILE_HELPER_PROTOCOL_H_
#define R0DUMP_FILE_HELPER_PROTOCOL_H_

#include <stddef.h>
#include <stdint.h>

namespace r0dump::file_helper {

inline constexpr uint32_t kProtocolMagic = 0x52304648u; // R0FH
inline constexpr uint16_t kProtocolVersion = 1u;
inline constexpr uint32_t kExportProtocolMagic = 0x52304558u; // R0EX
inline constexpr uint16_t kExportProtocolVersion = 1u;
inline constexpr uint32_t kExportResponseMagic = 0x52304552u; // R0ER
inline constexpr size_t kPackageBytes = 96u;
inline constexpr size_t kProcessBytes = 96u;
inline constexpr size_t kRunIdBytes = 65u;
inline constexpr size_t kArchivePathBytes = 256u;
inline constexpr size_t kSha256Bytes = 65u;
inline constexpr size_t kExportDetailBytes = 256u;

enum class Status : int32_t {
  kOk = 0,
  kInvalidCaller = 1,
  kDisabled = 2,
  kConfigMismatch = 3,
  kStorageUnavailable = 4,
  kExists = 5,
  kPermissionDenied = 6,
  kTimeout = 7,
  kProtocolError = 8,
  kInternalError = 9,
  kNoRuns = 10,
  kBusy = 11,
  kRepairFailed = 12,
  kArchiveFailed = 13,
};

#pragma pack(push, 1)
struct Request {
  uint32_t magic;
  uint16_t version;
  uint16_t size;
  uint64_t generation;
  uint32_t user_id;
  uint32_t target_uid;
  uint32_t target_pid;
  char package_name[kPackageBytes];
  char process_name[kProcessBytes];
  char run_id[kRunIdBytes];
};

struct Response {
  uint32_t magic;
  uint16_t version;
  uint16_t size;
  int32_t status;
};

struct ExportRequest {
  uint32_t magic;
  uint16_t version;
  uint16_t size;
  uint32_t user_id;
  uint32_t requester_uid;
  uint32_t apk_count;
  char package_name[kPackageBytes];
};

struct ExportResponse {
  uint32_t magic;
  uint16_t version;
  uint16_t size;
  int32_t status;
  uint32_t remote_artifact_count;
  uint32_t record_count;
  uint32_t verified;
  uint32_t repaired;
  uint32_t partial;
  uint32_t usable_outputs;
  uint32_t codeitems_backfilled;
  char run_id[kRunIdBytes];
  char archive_path[kArchivePathBytes];
  char sha256[kSha256Bytes];
  char detail[kExportDetailBytes];
};
#pragma pack(pop)

static_assert(sizeof(Request) == 285u);
static_assert(sizeof(Response) == 12u);
static_assert(sizeof(ExportRequest) == 116u);
static_assert(sizeof(ExportResponse) == 682u);

} // namespace r0dump::file_helper

#endif // R0DUMP_FILE_HELPER_PROTOCOL_H_
