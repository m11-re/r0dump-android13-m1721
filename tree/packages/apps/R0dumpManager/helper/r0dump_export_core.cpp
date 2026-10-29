#include "r0dump_export_core.h"

#include "r0dump_file_helper_core.h"

#include <fcntl.h>
#include <openssl/sha.h>
#include <sys/stat.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif
#include <unistd.h>
#include <ziparchive/zip_archive.h>
#include <ziparchive/zip_writer.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <string_view>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

#include <json/json.h>

namespace r0dump::file_helper {
namespace {

namespace fs = std::filesystem;

constexpr char kAlgorithmVersion[] = "r0dump-device-repair-v1";
constexpr size_t kDexHeaderSize = 0x70u;
constexpr uint64_t kMaxExportBytes = 2ull * 1024ull * 1024ull * 1024ull;
constexpr uint64_t kMaxSingleFileBytes = 1024ull * 1024ull * 1024ull;
constexpr uint32_t kMaxExportFiles = 20000u;
constexpr uint32_t kMaxMapItems = 4096u;
constexpr mode_t kDirectoryMode = 02770;
constexpr mode_t kFileMode = 0660;

struct DexLayout {
  uint32_t file_size;
  uint32_t header_size;
  uint32_t map_off;
  uint32_t data_off;
  uint32_t data_size;
  std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> sections;
};

struct Validation {
  std::string kind;
  std::string reason;
  std::optional<DexLayout> layout;
};

struct MethodInfo {
  std::string class_name;
  std::string method_name;
  std::string descriptor;
  uint32_t class_def_index;
  uint32_t code_off;
};

using MethodCatalog = std::map<uint32_t, MethodInfo>;

struct DexCandidate {
  fs::path artifact;
  std::string relative_artifact;
  std::string digest;
  MethodCatalog catalog;
};

struct ProcessSummary {
  uint32_t record_count = 0u;
  uint32_t verified = 0u;
  uint32_t repaired = 0u;
  uint32_t partial = 0u;
  uint32_t usable_outputs = 0u;
  uint32_t codeitems_backfilled = 0u;
  Json::Value report{Json::objectValue};
};

struct RunCandidate {
  fs::path path;
  std::string run_id;
  fs::file_time_type newest;
  uint32_t artifact_count;
};

bool CheckRange(uint64_t offset, uint64_t size, uint64_t limit) {
  return offset <= limit && size <= limit - offset;
}

uint16_t ReadU16(const std::vector<uint8_t> &data, size_t offset) {
  return static_cast<uint16_t>(data[offset]) |
         static_cast<uint16_t>(data[offset + 1u]) << 8u;
}

uint32_t ReadU32(const std::vector<uint8_t> &data, size_t offset) {
  return static_cast<uint32_t>(data[offset]) |
         static_cast<uint32_t>(data[offset + 1u]) << 8u |
         static_cast<uint32_t>(data[offset + 2u]) << 16u |
         static_cast<uint32_t>(data[offset + 3u]) << 24u;
}

void WriteU32(std::vector<uint8_t> *data, size_t offset, uint32_t value) {
  (*data)[offset] = static_cast<uint8_t>(value);
  (*data)[offset + 1u] = static_cast<uint8_t>(value >> 8u);
  (*data)[offset + 2u] = static_cast<uint8_t>(value >> 16u);
  (*data)[offset + 3u] = static_cast<uint8_t>(value >> 24u);
}

std::string Hex(const uint8_t *bytes, size_t size) {
  std::ostringstream stream;
  stream << std::hex << std::setfill('0');
  for (size_t i = 0; i < size; ++i) {
    stream << std::setw(2) << static_cast<unsigned>(bytes[i]);
  }
  return stream.str();
}

std::string Sha256(const std::vector<uint8_t> &data) {
  std::array<uint8_t, SHA256_DIGEST_LENGTH> digest{};
  SHA256(data.data(), data.size(), digest.data());
  return Hex(digest.data(), digest.size());
}

std::string Sha256File(const fs::path &path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    return {};
  }
  SHA256_CTX context;
  SHA256_Init(&context);
  std::array<char, 64 * 1024> buffer{};
  while (input) {
    input.read(buffer.data(), buffer.size());
    const std::streamsize count = input.gcount();
    if (count > 0) {
      SHA256_Update(&context, buffer.data(), static_cast<size_t>(count));
    }
  }
  std::array<uint8_t, SHA256_DIGEST_LENGTH> digest{};
  SHA256_Final(digest.data(), &context);
  return Hex(digest.data(), digest.size());
}

bool ReadFile(const fs::path &path, std::vector<uint8_t> *data) {
  std::error_code error;
  const uint64_t size = fs::file_size(path, error);
  if (error || size > kMaxSingleFileBytes ||
      size > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
    return false;
  }
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    return false;
  }
  data->assign(static_cast<size_t>(size), 0u);
  if (size != 0u) {
    input.read(reinterpret_cast<char *>(data->data()),
               static_cast<std::streamsize>(size));
  }
  return input.good() || input.eof();
}

bool WriteFile(const fs::path &path, const std::vector<uint8_t> &data) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  if (!output) {
    return false;
  }
  if (!data.empty()) {
    output.write(reinterpret_cast<const char *>(data.data()),
                 static_cast<std::streamsize>(data.size()));
  }
  output.close();
  chmod(path.c_str(), kFileMode);
  return output.good();
}

bool WriteText(const fs::path &path, const std::string &text) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  if (!output) {
    return false;
  }
  output << text;
  output.close();
  chmod(path.c_str(), kFileMode);
  return output.good();
}

std::string JsonText(const Json::Value &value) {
  Json::StreamWriterBuilder builder;
  builder["indentation"] = "  ";
  return Json::writeString(builder, value) + "\n";
}

bool ReadJson(const fs::path &path, Json::Value *value) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    return false;
  }
  Json::CharReaderBuilder builder;
  std::string errors;
  return Json::parseFromStream(builder, input, value, &errors) &&
         value->isObject();
}

bool SafeRelativePath(std::string_view value) {
  if (value.empty() || value.front() == '/') {
    return false;
  }
  fs::path path(value);
  if (path.is_absolute()) {
    return false;
  }
  for (const fs::path &part : path) {
    if (part == ".." || part == ".") {
      return false;
    }
  }
  return true;
}

std::optional<std::vector<std::tuple<uint16_t, uint32_t, uint32_t>>>
ParseMap(const std::vector<uint8_t> &data, uint32_t map_off,
         uint32_t file_size) {
  if (!CheckRange(map_off, 4u, file_size)) {
    return std::nullopt;
  }
  const uint32_t count = ReadU32(data, map_off);
  if (count > kMaxMapItems ||
      !CheckRange(static_cast<uint64_t>(map_off) + 4u,
                  static_cast<uint64_t>(count) * 12u, file_size)) {
    return std::nullopt;
  }
  const std::map<uint16_t, uint32_t> fixed_sizes = {
      {0x0000u, static_cast<uint32_t>(kDexHeaderSize)},
      {0x0001u, 4u},
      {0x0002u, 4u},
      {0x0003u, 12u},
      {0x0004u, 8u},
      {0x0005u, 8u},
      {0x0006u, 32u}};
  std::set<uint16_t> seen;
  uint32_t previous_offset = 0u;
  bool first = true;
  std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> entries;
  for (uint32_t index = 0u; index < count; ++index) {
    const size_t entry = static_cast<size_t>(map_off) + 4u + index * 12u;
    const uint16_t type = ReadU16(data, entry);
    const uint16_t unused = ReadU16(data, entry + 2u);
    const uint32_t size = ReadU32(data, entry + 4u);
    const uint32_t offset = ReadU32(data, entry + 8u);
    if (unused != 0u || !seen.insert(type).second ||
        (!first && offset < previous_offset)) {
      return std::nullopt;
    }
    first = false;
    previous_offset = offset;
    const auto fixed = fixed_sizes.find(type);
    if (fixed != fixed_sizes.end()) {
      if (size > file_size / fixed->second ||
          !CheckRange(offset, static_cast<uint64_t>(size) * fixed->second,
                      file_size)) {
        return std::nullopt;
      }
    } else if (type == 0x1000u) {
      if (size != 1u || offset != map_off) {
        return std::nullopt;
      }
    } else if (size != 0u && !CheckRange(offset, 1u, file_size)) {
      return std::nullopt;
    }
    entries.emplace_back(type, size, offset);
  }
  if (std::find(entries.begin(), entries.end(),
                std::make_tuple(static_cast<uint16_t>(0x0000u), 1u, 0u)) ==
          entries.end() ||
      std::find(entries.begin(), entries.end(),
                std::make_tuple(static_cast<uint16_t>(0x1000u), 1u, map_off)) ==
          entries.end()) {
    return std::nullopt;
  }
  return entries;
}

Validation ValidateDex(const std::vector<uint8_t> &data) {
  if (data.size() < 8u) {
    return {"unknown", "truncated_header", std::nullopt};
  }
  if (std::equal(data.begin(), data.begin() + 4u, "cdex")) {
    return {"cdex", "compact_dex_not_repaired", std::nullopt};
  }
  if (!std::equal(data.begin(), data.begin() + 4u, "dex\n")) {
    return {"unknown", "missing_dex_magic", std::nullopt};
  }
  if (data[7] != 0u || data[4] < '0' || data[4] > '9' || data[5] < '0' ||
      data[5] > '9' || data[6] < '0' || data[6] > '9') {
    return {"standard", "invalid_dex_version", std::nullopt};
  }
  if (data.size() < kDexHeaderSize) {
    return {"standard", "truncated_header", std::nullopt};
  }
  uint32_t file_size = ReadU32(data, 32u);
  const uint32_t header_size = ReadU32(data, 36u);
  const uint32_t endian_tag = ReadU32(data, 40u);
  const uint32_t link_size = ReadU32(data, 44u);
  const uint32_t link_off = ReadU32(data, 48u);
  const uint32_t map_off = ReadU32(data, 52u);
  const uint32_t data_size = ReadU32(data, 104u);
  const uint32_t data_off = ReadU32(data, 108u);
  if (header_size != kDexHeaderSize) {
    return {"standard", "unsupported_header_size", std::nullopt};
  }
  if (endian_tag != 0x12345678u) {
    return {"standard", "unsupported_endian", std::nullopt};
  }
  if (file_size < kDexHeaderSize || file_size > data.size()) {
    return {"standard", "file_size_out_of_bounds", std::nullopt};
  }
  if ((link_size == 0u && link_off != 0u) ||
      (link_size != 0u && !CheckRange(link_off, link_size, file_size))) {
    return {"standard", "link_range_out_of_bounds", std::nullopt};
  }
  const std::array<std::tuple<size_t, size_t, uint32_t>, 6> sections = {{
      {56u, 60u, 4u},
      {64u, 68u, 4u},
      {72u, 76u, 12u},
      {80u, 84u, 8u},
      {88u, 92u, 8u},
      {96u, 100u, 32u},
  }};
  for (const auto &[size_offset, offset_offset, item_size] : sections) {
    const uint32_t count = ReadU32(data, size_offset);
    const uint32_t offset = ReadU32(data, offset_offset);
    if ((count == 0u && offset != 0u) ||
        (count != 0u &&
         (count > file_size / item_size ||
          !CheckRange(offset, static_cast<uint64_t>(count) * item_size,
                      file_size)))) {
      return {"standard", "section_out_of_bounds", std::nullopt};
    }
  }
  if (data_off < kDexHeaderSize ||
      !CheckRange(data_off, data_size, file_size)) {
    return {"standard", "data_range_out_of_bounds", std::nullopt};
  }
  if (map_off < data_off || map_off >= file_size) {
    return {"standard", "map_outside_data_section", std::nullopt};
  }
  auto map = ParseMap(data, map_off, file_size);
  if (!map.has_value()) {
    return {"standard", "map_out_of_bounds", std::nullopt};
  }
  // r0dump: packers such as ijiami append a trailer after the map section and inflate the header
  // file_size to cover it, which breaks the spec invariant data_off + data_size == file_size.
  // The map and every section it describes are intact, so the canonical image ends exactly at the
  // map list; report that as the layout size and let RepairDexFixed truncate and rewrite the
  // header.  Without this a complete decrypted DEX is discarded as data_range_out_of_bounds.
  if (static_cast<uint64_t>(data_off) + data_size != file_size) {
    const uint64_t map_end =
        static_cast<uint64_t>(map_off) + 4u + map->size() * 12u;
    if (map_end != static_cast<uint64_t>(data_off) + data_size) {
      return {"standard", "data_range_out_of_bounds", std::nullopt};
    }
    file_size = static_cast<uint32_t>(map_end);
  }
  return {"standard", "",
          DexLayout{file_size, header_size, map_off, data_off, data_size,
                    std::move(*map)}};
}

std::pair<std::optional<std::vector<uint8_t>>, Json::Value>
RepairDexFixed(const std::vector<uint8_t> &data) {
  const Validation validation = ValidateDex(data);
  Json::Value details(Json::objectValue);
  details["kind"] = validation.kind;
  details["output_kind"] = "dexfixed";
  if (!validation.layout.has_value()) {
    details["trust"] = "partial";
    details["reason"] = validation.reason;
    return {std::nullopt, details};
  }
  const DexLayout &layout = *validation.layout;
  std::vector<uint8_t> repaired(data.begin(), data.begin() + layout.file_size);
  // Truncating past the packer trailer changes the image length, so the header must agree or the
  // result stays self-inconsistent even though signature/checksum are rewritten below.
  if (ReadU32(repaired, 32u) != layout.file_size) {
    WriteU32(&repaired, 32u, layout.file_size);
  }
  const std::array<uint8_t, SHA_DIGEST_LENGTH> old_signature = [&]() {
    std::array<uint8_t, SHA_DIGEST_LENGTH> result{};
    std::copy_n(repaired.begin() + 12u, result.size(), result.begin());
    return result;
  }();
  const uint32_t old_checksum = ReadU32(repaired, 8u);
  const uint32_t expected_checksum_before =
      adler32(adler32(0L, Z_NULL, 0), repaired.data() + 12u,
              repaired.size() - 12u);
  std::array<uint8_t, SHA_DIGEST_LENGTH> signature{};
  SHA1(repaired.data() + 32u, repaired.size() - 32u, signature.data());
  std::copy(signature.begin(), signature.end(), repaired.begin() + 12u);
  const uint32_t expected_checksum_after =
      adler32(adler32(0L, Z_NULL, 0), repaired.data() + 12u,
              repaired.size() - 12u);
  WriteU32(&repaired, 8u, expected_checksum_after);
  Json::Value modified(Json::arrayValue);
  if (old_signature != signature) {
    modified.append("signature");
  }
  if (old_checksum != expected_checksum_after) {
    modified.append("checksum");
  }
  details["trust"] = modified.empty() ? "verified" : "repaired";
  details["file_size"] = layout.file_size;
  details["map_off"] = layout.map_off;
  details["checksum_valid_before"] =
      old_checksum == expected_checksum_before;
  details["checksum_repaired"] = old_checksum != expected_checksum_after;
  details["checksum_valid_after"] =
      ReadU32(repaired, 8u) == expected_checksum_after;
  details["modified_fields"] = modified;
  return {std::move(repaired), details};
}

// --- DEX rehydration -------------------------------------------------------
// Faithful port of art/tools/r0dump/r0dump_dex_rehydrate.py. Rebuilds a
// complete DEX 035 image from a headerless dexdata capture plus the RuntimeView
// (data_begin + displaced ID-table pointers + section counts) recorded beside
// it. The output is byte-for-byte identical to the host rehydrator so a device
// export matches what the Host tool would produce from the same evidence.

constexpr size_t kRehErr = SIZE_MAX;
constexpr size_t kRehHeaderSize = 0x70u;
constexpr uint32_t kRehEndian = 0x12345678u;
constexpr uint32_t kRehNoIndex = 0xFFFFFFFFu;

enum RehSection {
  kRehStringIds = 0, kRehTypeIds, kRehProtoIds,
  kRehFieldIds, kRehMethodIds, kRehClassDefs, kRehSectionCount
};
constexpr uint32_t kRehItemSize[kRehSectionCount] = {4u, 4u, 12u, 8u, 8u, 32u};
constexpr size_t kRehHeaderPos[kRehSectionCount] = {56u, 64u, 72u, 80u, 88u, 96u};
constexpr const char *kRehSectionName[kRehSectionCount] = {
    "string_ids", "type_ids", "proto_ids", "field_ids", "method_ids",
    "class_defs"};

struct RehLayout {
  size_t offsets[kRehSectionCount];
  uint32_t counts[kRehSectionCount];
};

struct RehClassData {
  std::vector<std::pair<uint32_t, uint32_t>> static_fields;
  std::vector<std::pair<uint32_t, uint32_t>> instance_fields;
  std::vector<std::array<uint32_t, 3>> direct_methods;
  std::vector<std::array<uint32_t, 3>> virtual_methods;
};

size_t RehAlign4(size_t value) { return (value + 3u) & ~static_cast<size_t>(3u); }

uint32_t RehU32(const std::vector<uint8_t> &raw, size_t offset, bool *ok) {
  if (offset + 4u > raw.size()) {
    *ok = false;
    return 0u;
  }
  return static_cast<uint32_t>(raw[offset]) |
         static_cast<uint32_t>(raw[offset + 1u]) << 8u |
         static_cast<uint32_t>(raw[offset + 2u]) << 16u |
         static_cast<uint32_t>(raw[offset + 3u]) << 24u;
}

uint16_t RehU16(const std::vector<uint8_t> &raw, size_t offset, bool *ok) {
  if (offset + 2u > raw.size()) {
    *ok = false;
    return 0u;
  }
  return static_cast<uint16_t>(static_cast<uint16_t>(raw[offset]) |
                               static_cast<uint16_t>(raw[offset + 1u]) << 8u);
}

uint32_t RehUleb(const std::vector<uint8_t> &raw, size_t *cursor, bool *ok) {
  uint32_t value = 0u;
  unsigned shift = 0u;
  for (int index = 0; index < 5; ++index) {
    if (*cursor >= raw.size()) {
      *ok = false;
      return 0u;
    }
    const uint8_t byte = raw[(*cursor)++];
    value |= static_cast<uint32_t>(byte & 0x7fu) << shift;
    if (byte < 0x80u) {
      return value;
    }
    shift += 7u;
  }
  *ok = false;
  return 0u;
}

int64_t RehSleb(const std::vector<uint8_t> &raw, size_t *cursor, bool *ok) {
  int64_t value = 0;
  unsigned shift = 0u;
  for (int index = 0; index < 5; ++index) {
    if (*cursor >= raw.size()) {
      *ok = false;
      return 0;
    }
    const uint8_t byte = raw[(*cursor)++];
    value |= static_cast<int64_t>(byte & 0x7fu) << shift;
    shift += 7u;
    if (byte < 0x80u) {
      if (byte & 0x40u) {
        value -= (static_cast<int64_t>(1) << shift);
      }
      return value;
    }
  }
  *ok = false;
  return 0;
}

void RehAppendUleb(std::vector<uint8_t> *out, uint32_t value) {
  while (true) {
    const uint8_t byte = value & 0x7fu;
    value >>= 7u;
    out->push_back(static_cast<uint8_t>(byte | (value ? 0x80u : 0u)));
    if (!value) {
      return;
    }
  }
}

// Data-item walkers: return the exclusive end offset, or kRehErr if malformed.
size_t RehStringDataEnd(const std::vector<uint8_t> &raw, size_t offset) {
  bool ok = true;
  size_t cursor = offset;
  RehUleb(raw, &cursor, &ok);
  if (!ok) {
    return kRehErr;
  }
  while (cursor < raw.size()) {
    if (raw[cursor] == 0u) {
      return cursor + 1u;
    }
    ++cursor;
  }
  return kRehErr;
}

size_t RehTypeListEnd(const std::vector<uint8_t> &raw, size_t offset) {
  bool ok = true;
  const uint32_t size = RehU32(raw, offset, &ok);
  if (!ok) {
    return kRehErr;
  }
  const size_t end = offset + 4u + static_cast<size_t>(size) * 2u;
  if (end > raw.size()) {
    return kRehErr;
  }
  return end;
}

size_t RehEncodedValueEnd(const std::vector<uint8_t> &raw, size_t offset) {
  if (offset >= raw.size()) {
    return kRehErr;
  }
  const uint8_t header = raw[offset];
  const uint8_t value_type = header & 0x1fu;
  const uint8_t value_arg = header >> 5u;
  size_t cursor = offset + 1u;
  auto scalar_width = [](uint8_t type) -> int {
    switch (type) {
      case 0x00: return 1;
      case 0x02: return 2;
      case 0x03: return 2;
      case 0x04: return 4;
      case 0x06: return 8;
      case 0x10: return 4;
      case 0x11: return 8;
      case 0x15: return 4;
      case 0x16: return 4;
      case 0x17: return 4;
      case 0x18: return 4;
      case 0x19: return 4;
      case 0x1a: return 4;
      case 0x1b: return 4;
      default: return -1;
    }
  };
  if (value_type == 0x1cu) {  // VALUE_ARRAY
    if (value_arg) {
      return kRehErr;
    }
    bool ok = true;
    const uint32_t size = RehUleb(raw, &cursor, &ok);
    if (!ok) {
      return kRehErr;
    }
    for (uint32_t index = 0u; index < size; ++index) {
      cursor = RehEncodedValueEnd(raw, cursor);
      if (cursor == kRehErr) {
        return kRehErr;
      }
    }
  } else if (value_type == 0x1du) {  // VALUE_ANNOTATION
    if (value_arg) {
      return kRehErr;
    }
    bool ok = true;
    RehUleb(raw, &cursor, &ok);  // type_idx
    const uint32_t size = RehUleb(raw, &cursor, &ok);
    if (!ok) {
      return kRehErr;
    }
    for (uint32_t index = 0u; index < size; ++index) {
      RehUleb(raw, &cursor, &ok);  // name_idx
      if (!ok) {
        return kRehErr;
      }
      cursor = RehEncodedValueEnd(raw, cursor);
      if (cursor == kRehErr) {
        return kRehErr;
      }
    }
  } else if (const int width = scalar_width(value_type); width >= 0) {
    if (value_arg >= width) {
      return kRehErr;
    }
    cursor += static_cast<size_t>(value_arg) + 1u;
  } else if (value_type == 0x1eu) {  // VALUE_NULL
    if (value_arg) {
      return kRehErr;
    }
  } else if (value_type == 0x1fu) {  // VALUE_BOOLEAN
    if (value_arg > 1u) {
      return kRehErr;
    }
  } else {
    return kRehErr;
  }
  if (cursor > raw.size()) {
    return kRehErr;
  }
  return cursor;
}

size_t RehEncodedArrayEnd(const std::vector<uint8_t> &raw, size_t offset) {
  bool ok = true;
  size_t cursor = offset;
  const uint32_t size = RehUleb(raw, &cursor, &ok);
  if (!ok) {
    return kRehErr;
  }
  for (uint32_t index = 0u; index < size; ++index) {
    cursor = RehEncodedValueEnd(raw, cursor);
    if (cursor == kRehErr) {
      return kRehErr;
    }
  }
  return cursor;
}

size_t RehEncodedAnnotationEnd(const std::vector<uint8_t> &raw, size_t offset) {
  bool ok = true;
  size_t cursor = offset;
  RehUleb(raw, &cursor, &ok);  // type_idx
  const uint32_t size = RehUleb(raw, &cursor, &ok);
  if (!ok) {
    return kRehErr;
  }
  for (uint32_t index = 0u; index < size; ++index) {
    RehUleb(raw, &cursor, &ok);  // name_idx
    if (!ok) {
      return kRehErr;
    }
    cursor = RehEncodedValueEnd(raw, cursor);
    if (cursor == kRehErr) {
      return kRehErr;
    }
  }
  return cursor;
}

size_t RehAnnotationItemEnd(const std::vector<uint8_t> &raw, size_t offset) {
  if (offset >= raw.size()) {
    return kRehErr;
  }
  if (raw[offset] > 2u) {
    return kRehErr;
  }
  return RehEncodedAnnotationEnd(raw, offset + 1u);
}

size_t RehCodeItemEnd(const std::vector<uint8_t> &raw, size_t offset) {
  if (offset + 16u > raw.size()) {
    return kRehErr;
  }
  bool ok = true;
  const uint16_t tries_size = RehU16(raw, offset + 6u, &ok);
  const uint32_t insns_size = RehU32(raw, offset + 12u, &ok);
  if (!ok) {
    return kRehErr;
  }
  size_t cursor = offset + 16u + static_cast<size_t>(insns_size) * 2u;
  if (cursor > raw.size()) {
    return kRehErr;
  }
  if (!tries_size) {
    return cursor;
  }
  cursor = RehAlign4(cursor) + static_cast<size_t>(tries_size) * 8u;
  const uint32_t handlers_size = RehUleb(raw, &cursor, &ok);
  if (!ok) {
    return kRehErr;
  }
  for (uint32_t index = 0u; index < handlers_size; ++index) {
    const int64_t handler_count = RehSleb(raw, &cursor, &ok);
    if (!ok) {
      return kRehErr;
    }
    const int64_t magnitude = handler_count < 0 ? -handler_count : handler_count;
    for (int64_t entry = 0; entry < magnitude; ++entry) {
      RehUleb(raw, &cursor, &ok);  // type_idx
      RehUleb(raw, &cursor, &ok);  // address
    }
    if (handler_count <= 0) {
      RehUleb(raw, &cursor, &ok);  // catch_all_addr
    }
    if (!ok) {
      return kRehErr;
    }
  }
  if (cursor > raw.size()) {
    return kRehErr;
  }
  return cursor;
}

size_t RehDebugInfoEnd(const std::vector<uint8_t> &raw, size_t offset) {
  bool ok = true;
  size_t cursor = offset;
  RehUleb(raw, &cursor, &ok);  // line_start
  const uint32_t parameters_size = RehUleb(raw, &cursor, &ok);
  if (!ok) {
    return kRehErr;
  }
  for (uint32_t index = 0u; index < parameters_size; ++index) {
    RehUleb(raw, &cursor, &ok);  // uleb128p1 name index
  }
  if (!ok) {
    return kRehErr;
  }
  while (true) {
    if (cursor >= raw.size()) {
      return kRehErr;
    }
    const uint8_t opcode = raw[cursor++];
    if (opcode == 0u) {
      return cursor;
    }
    if (opcode == 1u) {
      RehUleb(raw, &cursor, &ok);
    } else if (opcode == 2u) {
      RehSleb(raw, &cursor, &ok);
    } else if (opcode == 3u) {
      for (int part = 0; part < 3; ++part) {
        RehUleb(raw, &cursor, &ok);
      }
    } else if (opcode == 4u) {
      for (int part = 0; part < 4; ++part) {
        RehUleb(raw, &cursor, &ok);
      }
    } else if (opcode == 5u || opcode == 6u) {
      RehUleb(raw, &cursor, &ok);
    }
    // Opcodes 7, 8, 9 and >= 10 (special range) carry no arguments.
    if (!ok) {
      return kRehErr;
    }
  }
}

bool RehParseClassData(const std::vector<uint8_t> &raw, size_t offset,
                       RehClassData *out, size_t *end) {
  if (offset == 0u || offset >= raw.size()) {
    return false;
  }
  bool ok = true;
  size_t cursor = offset;
  const uint32_t static_count = RehUleb(raw, &cursor, &ok);
  const uint32_t instance_count = RehUleb(raw, &cursor, &ok);
  const uint32_t direct_count = RehUleb(raw, &cursor, &ok);
  const uint32_t virtual_count = RehUleb(raw, &cursor, &ok);
  if (!ok) {
    return false;
  }
  auto read_fields = [&](uint32_t count,
                         std::vector<std::pair<uint32_t, uint32_t>> *dst) {
    uint32_t running = 0u;
    for (uint32_t index = 0u; index < count; ++index) {
      const uint32_t delta = RehUleb(raw, &cursor, &ok);
      const uint32_t access = RehUleb(raw, &cursor, &ok);
      if (!ok) {
        return;
      }
      running += delta;
      dst->emplace_back(running, access);
    }
  };
  auto read_methods = [&](uint32_t count,
                          std::vector<std::array<uint32_t, 3>> *dst) {
    uint32_t running = 0u;
    for (uint32_t index = 0u; index < count; ++index) {
      const uint32_t delta = RehUleb(raw, &cursor, &ok);
      const uint32_t access = RehUleb(raw, &cursor, &ok);
      const uint32_t code_off = RehUleb(raw, &cursor, &ok);
      if (!ok) {
        return;
      }
      running += delta;
      dst->push_back({running, access, code_off});
    }
  };
  read_fields(static_count, &out->static_fields);
  read_fields(instance_count, &out->instance_fields);
  read_methods(direct_count, &out->direct_methods);
  read_methods(virtual_count, &out->virtual_methods);
  if (!ok) {
    return false;
  }
  if (end) {
    *end = cursor;
  }
  return true;
}

std::vector<uint8_t> RehEncodeClassData(const RehClassData &parsed,
                                        size_t raw_base) {
  std::vector<uint8_t> out;
  RehAppendUleb(&out, static_cast<uint32_t>(parsed.static_fields.size()));
  RehAppendUleb(&out, static_cast<uint32_t>(parsed.instance_fields.size()));
  RehAppendUleb(&out, static_cast<uint32_t>(parsed.direct_methods.size()));
  RehAppendUleb(&out, static_cast<uint32_t>(parsed.virtual_methods.size()));
  auto emit_fields =
      [&](const std::vector<std::pair<uint32_t, uint32_t>> &values) {
        uint32_t previous = 0u;
        for (const auto &[index, access] : values) {
          RehAppendUleb(&out, index - previous);
          RehAppendUleb(&out, access);
          previous = index;
        }
      };
  auto emit_methods =
      [&](const std::vector<std::array<uint32_t, 3>> &values) {
        uint32_t previous = 0u;
        for (const auto &method : values) {
          RehAppendUleb(&out, method[0] - previous);
          RehAppendUleb(&out, method[1]);
          RehAppendUleb(&out, method[2]
                                  ? static_cast<uint32_t>(raw_base + method[2])
                                  : 0u);
          previous = method[0];
        }
      };
  emit_fields(parsed.static_fields);
  emit_fields(parsed.instance_fields);
  emit_methods(parsed.direct_methods);
  emit_methods(parsed.virtual_methods);
  return out;
}

bool RehParseHex(const Json::Value &value, uint64_t *out) {
  if (!value.isString()) {
    return false;
  }
  const std::string text = value.asString();
  errno = 0;
  char *end = nullptr;
  const unsigned long long parsed = strtoull(text.c_str(), &end, 16);
  if (end == text.c_str() || errno != 0) {
    return false;
  }
  *out = static_cast<uint64_t>(parsed) & ((static_cast<uint64_t>(1) << 56) - 1u);
  return true;
}

bool RehParseRuntimeLayout(const std::vector<uint8_t> &raw,
                           const Json::Value &record, RehLayout *out) {
  const Json::Value &runtime_view = record["runtime_view"];
  if (!runtime_view.isObject()) {
    return false;
  }
  uint64_t base = 0u;
  if (!RehParseHex(runtime_view["data_begin"], &base)) {
    return false;
  }
  for (int section = 0; section < kRehSectionCount; ++section) {
    uint64_t pointer = 0u;
    if (!RehParseHex(runtime_view[kRehSectionName[section]], &pointer)) {
      return false;
    }
    if (pointer < base) {
      return false;
    }
    const uint64_t offset = pointer - base;
    if (offset >= raw.size()) {
      return false;
    }
    out->offsets[section] = static_cast<size_t>(offset);
  }
  const Json::Value &record_counts = record["section_counts_current"];
  const Json::Value &counts = record_counts.isObject()
                                  ? record_counts
                                  : runtime_view["section_counts_current"];
  if (!counts.isObject()) {
    return false;  // Only the epoch_current path is supported on device.
  }
  for (int section = 0; section < kRehSectionCount; ++section) {
    const Json::Value &value = counts[kRehSectionName[section]];
    if (!value.isIntegral()) {
      return false;
    }
    const Json::Int64 count = value.asInt64();
    if (count < 0) {
      return false;
    }
    out->counts[section] = static_cast<uint32_t>(count);
  }
  return true;
}

bool RehValidateLayout(const std::vector<uint8_t> &raw, const RehLayout &layout) {
  bool ok = true;
  const uint32_t *counts = layout.counts;
  for (int section = 0; section < kRehSectionCount; ++section) {
    if (layout.offsets[section] +
            static_cast<size_t>(counts[section]) * kRehItemSize[section] >
        raw.size()) {
      return false;
    }
  }
  for (uint32_t index = 0u; index < counts[kRehStringIds]; ++index) {
    const uint32_t offset =
        RehU32(raw, layout.offsets[kRehStringIds] + index * 4u, &ok);
    if (!ok || offset >= raw.size()) {
      return false;
    }
  }
  for (uint32_t index = 0u; index < counts[kRehTypeIds]; ++index) {
    const uint32_t descriptor =
        RehU32(raw, layout.offsets[kRehTypeIds] + index * 4u, &ok);
    if (!ok || descriptor >= counts[kRehStringIds]) {
      return false;
    }
  }
  for (uint32_t index = 0u; index < counts[kRehProtoIds]; ++index) {
    const size_t base =
        layout.offsets[kRehProtoIds] + static_cast<size_t>(index) * 12u;
    const uint32_t shorty = RehU32(raw, base, &ok);
    const uint32_t result = RehU32(raw, base + 4u, &ok);
    const uint32_t parameters = RehU32(raw, base + 8u, &ok);
    if (!ok || shorty >= counts[kRehStringIds] ||
        result >= counts[kRehTypeIds] || parameters >= raw.size()) {
      return false;
    }
  }
  for (int which = 0; which < 2; ++which) {
    const RehSection section = which == 0 ? kRehFieldIds : kRehMethodIds;
    for (uint32_t index = 0u; index < counts[section]; ++index) {
      const size_t base =
          layout.offsets[section] + static_cast<size_t>(index) * 8u;
      const uint16_t first = RehU16(raw, base, &ok);
      const uint16_t second = RehU16(raw, base + 2u, &ok);
      const uint32_t third = RehU32(raw, base + 4u, &ok);
      if (!ok || first >= counts[kRehTypeIds] ||
          third >= counts[kRehStringIds]) {
        return false;
      }
      if (section == kRehFieldIds && second >= counts[kRehTypeIds]) {
        return false;
      }
      if (section == kRehMethodIds && second >= counts[kRehProtoIds]) {
        return false;
      }
    }
  }
  for (uint32_t index = 0u; index < counts[kRehClassDefs]; ++index) {
    const size_t base =
        layout.offsets[kRehClassDefs] + static_cast<size_t>(index) * 32u;
    uint32_t fields[8];
    for (int part = 0; part < 8; ++part) {
      fields[part] = RehU32(raw, base + static_cast<size_t>(part) * 4u, &ok);
    }
    if (!ok) {
      return false;
    }
    const uint32_t class_idx = fields[0];
    const uint32_t super_idx = fields[2];
    const uint32_t interfaces = fields[3];
    const uint32_t source = fields[4];
    const uint32_t annotations = fields[5];
    const uint32_t class_data = fields[6];
    const uint32_t static_values = fields[7];
    if (class_idx >= counts[kRehTypeIds] ||
        (super_idx != kRehNoIndex && super_idx >= counts[kRehTypeIds])) {
      return false;
    }
    for (const uint32_t pointed : {interfaces, annotations, class_data,
                                   static_values}) {
      if (pointed && pointed >= raw.size()) {
        return false;
      }
    }
    if (source != kRehNoIndex && source >= counts[kRehStringIds]) {
      return false;
    }
    if (class_data) {
      RehClassData parsed;
      if (!RehParseClassData(raw, class_data, &parsed, nullptr)) {
        return false;
      }
      auto check_methods =
          [&](const std::vector<std::array<uint32_t, 3>> &methods) -> bool {
        for (const auto &method : methods) {
          if (method[0] >= counts[kRehMethodIds] ||
              (method[2] && method[2] >= raw.size())) {
            return false;
          }
          if (method[2] && method[2] + 16u > raw.size()) {
            return false;
          }
        }
        return true;
      };
      if (!check_methods(parsed.direct_methods) ||
          !check_methods(parsed.virtual_methods)) {
        return false;
      }
    }
  }
  return true;
}

struct RehCollectCtx {
  const std::vector<uint8_t> *raw;
  std::map<uint16_t, std::map<size_t, size_t>> *groups;
  std::set<size_t> annotation_sets;
  std::set<size_t> annotation_ref_lists;
  bool ok = true;
};

void RehAddItem(RehCollectCtx *ctx, uint16_t type, uint32_t offset,
                size_t (*end_fn)(const std::vector<uint8_t> &, size_t)) {
  if (!ctx->ok || !offset) {
    return;
  }
  if (offset >= ctx->raw->size()) {
    ctx->ok = false;
    return;
  }
  const size_t end = end_fn(*ctx->raw, offset);
  if (end == kRehErr) {
    ctx->ok = false;
    return;
  }
  (*ctx->groups)[type][offset] = end;
}

void RehAddAnnotationSet(RehCollectCtx *ctx, uint32_t offset) {
  if (!ctx->ok || !offset || ctx->annotation_sets.count(offset)) {
    return;
  }
  if (offset + 4u > ctx->raw->size()) {
    ctx->ok = false;
    return;
  }
  ctx->annotation_sets.insert(offset);
  bool ok = true;
  const uint32_t count = RehU32(*ctx->raw, offset, &ok);
  const size_t end =
      static_cast<size_t>(offset) + 4u + static_cast<size_t>(count) * 4u;
  if (!ok || end > ctx->raw->size()) {
    ctx->ok = false;
    return;
  }
  (*ctx->groups)[0x1003u][offset] = end;
  for (uint32_t index = 0u; index < count; ++index) {
    RehAddItem(ctx, 0x2004u, RehU32(*ctx->raw, offset + 4u + index * 4u, &ok),
               RehAnnotationItemEnd);
    if (!ok) {
      ctx->ok = false;
      return;
    }
  }
}

void RehAddAnnotationRefList(RehCollectCtx *ctx, uint32_t offset) {
  if (!ctx->ok || !offset || ctx->annotation_ref_lists.count(offset)) {
    return;
  }
  if (offset + 4u > ctx->raw->size()) {
    ctx->ok = false;
    return;
  }
  ctx->annotation_ref_lists.insert(offset);
  bool ok = true;
  const uint32_t count = RehU32(*ctx->raw, offset, &ok);
  const size_t end =
      static_cast<size_t>(offset) + 4u + static_cast<size_t>(count) * 4u;
  if (!ok || end > ctx->raw->size()) {
    ctx->ok = false;
    return;
  }
  (*ctx->groups)[0x1002u][offset] = end;
  for (uint32_t index = 0u; index < count; ++index) {
    RehAddAnnotationSet(ctx, RehU32(*ctx->raw, offset + 4u + index * 4u, &ok));
    if (!ok) {
      ctx->ok = false;
      return;
    }
  }
}

bool RehCollectReachable(const std::vector<uint8_t> &raw,
                         const RehLayout &layout,
                         std::map<uint16_t, std::map<size_t, size_t>> *groups) {
  RehCollectCtx ctx;
  ctx.raw = &raw;
  ctx.groups = groups;
  bool ok = true;
  const uint32_t *counts = layout.counts;
  for (uint32_t index = 0u; index < counts[kRehStringIds]; ++index) {
    RehAddItem(&ctx, 0x2002u,
               RehU32(raw, layout.offsets[kRehStringIds] + index * 4u, &ok),
               RehStringDataEnd);
    if (!ok || !ctx.ok) {
      return false;
    }
  }
  for (uint32_t index = 0u; index < counts[kRehProtoIds]; ++index) {
    RehAddItem(
        &ctx, 0x1001u,
        RehU32(raw,
               layout.offsets[kRehProtoIds] + static_cast<size_t>(index) * 12u +
                   8u,
               &ok),
        RehTypeListEnd);
    if (!ok || !ctx.ok) {
      return false;
    }
  }
  for (uint32_t index = 0u; index < counts[kRehClassDefs]; ++index) {
    const size_t base =
        layout.offsets[kRehClassDefs] + static_cast<size_t>(index) * 32u;
    const uint32_t interfaces = RehU32(raw, base + 12u, &ok);
    const uint32_t annotations = RehU32(raw, base + 20u, &ok);
    const uint32_t class_data = RehU32(raw, base + 24u, &ok);
    const uint32_t static_values = RehU32(raw, base + 28u, &ok);
    if (!ok) {
      return false;
    }
    RehAddItem(&ctx, 0x1001u, interfaces, RehTypeListEnd);
    RehAddItem(&ctx, 0x2005u, static_values, RehEncodedArrayEnd);
    if (!ctx.ok) {
      return false;
    }
    if (annotations) {
      if (annotations + 16u > raw.size()) {
        return false;
      }
      const uint32_t fields_size = RehU32(raw, annotations + 4u, &ok);
      const uint32_t methods_size = RehU32(raw, annotations + 8u, &ok);
      const uint32_t parameters_size = RehU32(raw, annotations + 12u, &ok);
      if (!ok) {
        return false;
      }
      const size_t directory_end =
          static_cast<size_t>(annotations) + 16u +
          (static_cast<size_t>(fields_size) + methods_size + parameters_size) *
              8u;
      if (directory_end > raw.size()) {
        return false;
      }
      (*groups)[0x2006u][annotations] = directory_end;
      RehAddAnnotationSet(&ctx, RehU32(raw, annotations, &ok));
      if (!ok || !ctx.ok) {
        return false;
      }
      size_t cursor = static_cast<size_t>(annotations) + 16u;
      for (uint32_t entry = 0u; entry < fields_size + methods_size; ++entry) {
        RehAddAnnotationSet(&ctx, RehU32(raw, cursor + 4u, &ok));
        if (!ok || !ctx.ok) {
          return false;
        }
        cursor += 8u;
      }
      for (uint32_t entry = 0u; entry < parameters_size; ++entry) {
        RehAddAnnotationRefList(&ctx, RehU32(raw, cursor + 4u, &ok));
        if (!ok || !ctx.ok) {
          return false;
        }
        cursor += 8u;
      }
    }
    if (class_data) {
      RehClassData parsed;
      if (!RehParseClassData(raw, class_data, &parsed, nullptr)) {
        return false;
      }
      auto add_code =
          [&](const std::vector<std::array<uint32_t, 3>> &methods) -> bool {
        for (const auto &method : methods) {
          if (method[2]) {
            RehAddItem(&ctx, 0x2001u, method[2], RehCodeItemEnd);
            if (!ctx.ok) {
              return false;
            }
            RehAddItem(&ctx, 0x2003u,
                       RehU32(raw, static_cast<size_t>(method[2]) + 8u, &ok),
                       RehDebugInfoEnd);
            if (!ok || !ctx.ok) {
              return false;
            }
          }
        }
        return true;
      };
      if (!add_code(parsed.direct_methods) ||
          !add_code(parsed.virtual_methods)) {
        return false;
      }
    }
  }
  return ctx.ok;
}

void RehPatchAnnotationTree(std::vector<uint8_t> *output, uint32_t at,
                            size_t raw_base,
                            std::set<std::pair<int, uint32_t>> *seen,
                            bool *ok) {
  if (!*ok || !at) {
    return;
  }
  if (!seen->insert({0, at}).second) {  // 0 == directory
    return;
  }
  const size_t physical = raw_base + at;
  if (physical + 16u > output->size()) {
    *ok = false;
    return;
  }
  const uint32_t class_annotations = ReadU32(*output, physical);
  const uint32_t fields_size = ReadU32(*output, physical + 4u);
  const uint32_t methods_size = ReadU32(*output, physical + 8u);
  auto shifted = [&](uint32_t old) -> uint32_t {
    if (old >= output->size() - raw_base) {
      *ok = false;
      return 0u;
    }
    return old ? static_cast<uint32_t>(raw_base + old) : 0u;
  };
  auto annotation_set = [&](uint32_t offset) {
    if (!*ok || !offset) {
      return;
    }
    if (!seen->insert({1, offset}).second) {  // 1 == set
      return;
    }
    const size_t set_physical = raw_base + offset;
    if (set_physical + 4u > output->size()) {
      *ok = false;
      return;
    }
    const uint32_t count = ReadU32(*output, set_physical);
    for (uint32_t index = 0u; index < count; ++index) {
      const size_t position = set_physical + 4u + static_cast<size_t>(index) * 4u;
      if (position + 4u > output->size()) {
        *ok = false;
        return;
      }
      WriteU32(output, position, shifted(ReadU32(*output, position)));
      if (!*ok) {
        return;
      }
    }
  };
  annotation_set(class_annotations);
  if (!*ok) {
    return;
  }
  WriteU32(output, physical, shifted(class_annotations));
  if (!*ok) {
    return;
  }
  size_t cursor = physical + 16u;
  for (uint32_t entry = 0u; entry < fields_size + methods_size; ++entry) {
    const size_t value_position = cursor + 4u;
    if (value_position + 4u > output->size()) {
      *ok = false;
      return;
    }
    const uint32_t old = ReadU32(*output, value_position);
    annotation_set(old);
    if (!*ok) {
      return;
    }
    WriteU32(output, value_position, shifted(old));
    if (!*ok) {
      return;
    }
    cursor += 8u;
  }
}

std::optional<std::vector<uint8_t>> RehBuild(const std::vector<uint8_t> &raw,
                                             const RehLayout &layout) {
  if (!RehValidateLayout(raw, layout)) {
    return std::nullopt;
  }
  std::map<uint16_t, std::map<size_t, size_t>> groups;
  if (!RehCollectReachable(raw, layout, &groups)) {
    return std::nullopt;
  }
  const uint32_t *counts = layout.counts;

  size_t table_offsets[kRehSectionCount];
  size_t cursor = kRehHeaderSize;
  for (int section = 0; section < kRehSectionCount; ++section) {
    cursor = RehAlign4(cursor);
    table_offsets[section] = cursor;
    cursor += static_cast<size_t>(counts[section]) * kRehItemSize[section];
  }
  const size_t raw_base = RehAlign4(cursor);

  std::vector<uint8_t> output(raw_base + raw.size(), 0u);
  for (const auto &[type, items] : groups) {
    static_cast<void>(type);
    for (const auto &[old_offset, old_end] : items) {
      std::copy(raw.begin() + old_offset, raw.begin() + old_end,
                output.begin() + raw_base + old_offset);
    }
  }
  for (int section = 0; section < kRehSectionCount; ++section) {
    const size_t size = static_cast<size_t>(counts[section]) *
                        kRehItemSize[section];
    std::copy(raw.begin() + layout.offsets[section],
              raw.begin() + layout.offsets[section] + size,
              output.begin() + table_offsets[section]);
  }
  for (uint32_t index = 0u; index < counts[kRehStringIds]; ++index) {
    const size_t position = table_offsets[kRehStringIds] + index * 4u;
    WriteU32(&output, position,
             static_cast<uint32_t>(raw_base + ReadU32(output, position)));
  }
  for (uint32_t index = 0u; index < counts[kRehProtoIds]; ++index) {
    const size_t position =
        table_offsets[kRehProtoIds] + static_cast<size_t>(index) * 12u + 8u;
    const uint32_t old = ReadU32(output, position);
    WriteU32(&output, position,
             old ? static_cast<uint32_t>(raw_base + old) : 0u);
  }

  std::vector<uint32_t> class_data_order;
  std::map<uint32_t, uint32_t> class_data_cache;
  std::vector<uint32_t> annotations;
  bool ok = true;
  for (uint32_t index = 0u; index < counts[kRehClassDefs]; ++index) {
    const size_t source =
        layout.offsets[kRehClassDefs] + static_cast<size_t>(index) * 32u;
    const size_t destination =
        table_offsets[kRehClassDefs] + static_cast<size_t>(index) * 32u;
    uint32_t values[8];
    for (int part = 0; part < 8; ++part) {
      values[part] = RehU32(raw, source + static_cast<size_t>(part) * 4u, &ok);
    }
    if (!ok) {
      return std::nullopt;
    }
    const uint32_t interfaces = values[3];
    const uint32_t annotation_off = values[5];
    const uint32_t class_data_off = values[6];
    const uint32_t static_values = values[7];
    values[3] = interfaces ? static_cast<uint32_t>(raw_base + interfaces) : 0u;
    values[5] =
        annotation_off ? static_cast<uint32_t>(raw_base + annotation_off) : 0u;
    values[7] =
        static_values ? static_cast<uint32_t>(raw_base + static_values) : 0u;
    if (annotation_off) {
      annotations.push_back(annotation_off);
    }
    if (class_data_off) {
      if (class_data_cache.emplace(class_data_off, 0u).second) {
        class_data_order.push_back(class_data_off);
      }
    }
    for (int part = 0; part < 8; ++part) {
      WriteU32(&output, destination + static_cast<size_t>(part) * 4u,
               values[part]);
    }
  }

  if (auto it = groups.find(0x2001u); it != groups.end()) {
    for (const auto &[code_off, code_end] : it->second) {
      static_cast<void>(code_end);
      const size_t debug_position = raw_base + code_off + 8u;
      const uint32_t debug_off = ReadU32(output, debug_position);
      WriteU32(&output, debug_position,
               debug_off ? static_cast<uint32_t>(raw_base + debug_off) : 0u);
    }
  }
  std::set<std::pair<int, uint32_t>> annotation_seen;
  for (const uint32_t annotation : annotations) {
    RehPatchAnnotationTree(&output, annotation, raw_base, &annotation_seen,
                           &ok);
    if (!ok) {
      return std::nullopt;
    }
  }

  size_t class_data_base = RehAlign4(output.size());
  if (class_data_base > output.size()) {
    output.resize(class_data_base, 0u);
  }
  for (const uint32_t old : class_data_order) {
    class_data_cache[old] = static_cast<uint32_t>(output.size());
    RehClassData parsed;
    if (!RehParseClassData(raw, old, &parsed, nullptr)) {
      return std::nullopt;
    }
    const std::vector<uint8_t> encoded = RehEncodeClassData(parsed, raw_base);
    output.insert(output.end(), encoded.begin(), encoded.end());
  }
  for (uint32_t index = 0u; index < counts[kRehClassDefs]; ++index) {
    const size_t source =
        layout.offsets[kRehClassDefs] + static_cast<size_t>(index) * 32u;
    const uint32_t old_class_data = RehU32(raw, source + 24u, &ok);
    if (!ok) {
      return std::nullopt;
    }
    if (old_class_data) {
      WriteU32(&output,
               table_offsets[kRehClassDefs] + static_cast<size_t>(index) * 32u +
                   24u,
               class_data_cache[old_class_data]);
    }
  }

  size_t map_off = RehAlign4(output.size());
  if (map_off > output.size()) {
    output.resize(map_off, 0u);
  }
  std::vector<std::array<uint32_t, 3>> map_items;  // {type, size, offset}
  map_items.push_back({0x0000u, 1u, 0u});
  map_items.push_back({0x0001u, counts[kRehStringIds],
                       static_cast<uint32_t>(table_offsets[kRehStringIds])});
  map_items.push_back({0x0002u, counts[kRehTypeIds],
                       static_cast<uint32_t>(table_offsets[kRehTypeIds])});
  map_items.push_back({0x0003u, counts[kRehProtoIds],
                       static_cast<uint32_t>(table_offsets[kRehProtoIds])});
  map_items.push_back({0x0004u, counts[kRehFieldIds],
                       static_cast<uint32_t>(table_offsets[kRehFieldIds])});
  map_items.push_back({0x0005u, counts[kRehMethodIds],
                       static_cast<uint32_t>(table_offsets[kRehMethodIds])});
  map_items.push_back({0x0006u, counts[kRehClassDefs],
                       static_cast<uint32_t>(table_offsets[kRehClassDefs])});
  for (const auto &[type, items] : groups) {
    if (items.empty()) {
      continue;
    }
    map_items.push_back({static_cast<uint32_t>(type),
                         static_cast<uint32_t>(items.size()),
                         static_cast<uint32_t>(raw_base + items.begin()->first)});
  }
  if (!class_data_cache.empty()) {
    map_items.push_back({0x2000u,
                         static_cast<uint32_t>(class_data_cache.size()),
                         static_cast<uint32_t>(class_data_base)});
  }
  map_items.push_back({0x1000u, 1u, static_cast<uint32_t>(map_off)});
  std::sort(map_items.begin(), map_items.end(),
            [](const std::array<uint32_t, 3> &lhs,
               const std::array<uint32_t, 3> &rhs) {
              return lhs[2] < rhs[2];
            });
  {
    std::vector<uint8_t> header(4u, 0u);
    WriteU32(&header, 0u, static_cast<uint32_t>(map_items.size()));
    output.insert(output.end(), header.begin(), header.end());
  }
  for (const auto &item : map_items) {
    std::vector<uint8_t> entry(12u, 0u);
    entry[0] = static_cast<uint8_t>(item[0]);
    entry[1] = static_cast<uint8_t>(item[0] >> 8u);
    WriteU32(&entry, 4u, item[1]);
    WriteU32(&entry, 8u, item[2]);
    output.insert(output.end(), entry.begin(), entry.end());
  }

  const size_t file_size = output.size();
  static const uint8_t kMagic[8] = {'d', 'e', 'x', '\n', '0', '3', '5', '\0'};
  std::copy(kMagic, kMagic + 8, output.begin());
  WriteU32(&output, 32u, static_cast<uint32_t>(file_size));
  WriteU32(&output, 36u, static_cast<uint32_t>(kRehHeaderSize));
  WriteU32(&output, 40u, kRehEndian);
  WriteU32(&output, 52u, static_cast<uint32_t>(map_off));
  for (int section = 0; section < kRehSectionCount; ++section) {
    WriteU32(&output, kRehHeaderPos[section], counts[section]);
    WriteU32(&output, kRehHeaderPos[section] + 4u,
             static_cast<uint32_t>(table_offsets[section]));
  }
  size_t first_data_offset = SIZE_MAX;
  for (const auto &[type, items] : groups) {
    static_cast<void>(type);
    if (!items.empty()) {
      first_data_offset = std::min(first_data_offset, items.begin()->first);
    }
  }
  if (first_data_offset == SIZE_MAX) {
    return std::nullopt;
  }
  const size_t data_off = raw_base + first_data_offset;
  WriteU32(&output, 104u, static_cast<uint32_t>(file_size - data_off));
  WriteU32(&output, 108u, static_cast<uint32_t>(data_off));
  SHA1(output.data() + 32u, output.size() - 32u, output.data() + 12u);
  WriteU32(&output, 8u,
           static_cast<uint32_t>(adler32(adler32(0L, Z_NULL, 0),
                                         output.data() + 12u,
                                         output.size() - 12u)));
  return output;
}

std::optional<std::vector<uint8_t>> RehydrateDexData(
    const std::vector<uint8_t> &raw, const Json::Value &record) {
  RehLayout layout;
  if (!RehParseRuntimeLayout(raw, record, &layout)) {
    return std::nullopt;
  }
  return RehBuild(raw, layout);
}

bool ReadUleb128(const std::vector<uint8_t> &data, size_t *offset, size_t limit,
                 uint32_t *value, std::string *error) {
  uint32_t result = 0u;
  for (uint32_t index = 0u; index < 5u; ++index) {
    if (*offset >= limit) {
      *error = "truncated_uleb128";
      return false;
    }
    const uint8_t byte = data[(*offset)++];
    result |= static_cast<uint32_t>(byte & 0x7fu) << (index * 7u);
    if ((byte & 0x80u) == 0u) {
      if (index == 4u && byte > 0x0fu) {
        *error = "uleb128_overflow";
        return false;
      }
      *value = result;
      return true;
    }
  }
  *error = "uleb128_overflow";
  return false;
}

bool ReadSleb128(const std::vector<uint8_t> &data, size_t *offset, size_t limit,
                 int32_t *value, std::string *error) {
  int64_t result = 0;
  uint32_t shift = 0u;
  for (uint32_t index = 0u; index < 5u; ++index) {
    if (*offset >= limit) {
      *error = "truncated_sleb128";
      return false;
    }
    const uint8_t byte = data[(*offset)++];
    result |= static_cast<int64_t>(byte & 0x7fu) << shift;
    shift += 7u;
    if ((byte & 0x80u) == 0u) {
      if (shift < 32u && (byte & 0x40u) != 0u) {
        result -= static_cast<int64_t>(1) << shift;
      }
      if (result < std::numeric_limits<int32_t>::min() ||
          result > std::numeric_limits<int32_t>::max()) {
        *error = "sleb128_overflow";
        return false;
      }
      *value = static_cast<int32_t>(result);
      return true;
    }
  }
  *error = "sleb128_overflow";
  return false;
}

bool MethodCodeEntries(
    const std::vector<uint8_t> &data, const DexLayout &layout,
    std::map<uint32_t, std::pair<uint32_t, uint32_t>> *entries,
    std::string *error) {
  const uint32_t class_count = ReadU32(data, 96u);
  const uint32_t class_off = ReadU32(data, 100u);
  const uint32_t method_count = ReadU32(data, 88u);
  for (uint32_t class_index = 0u; class_index < class_count; ++class_index) {
    const size_t class_def = static_cast<size_t>(class_off) + class_index * 32u;
    const uint32_t class_data_off = ReadU32(data, class_def + 24u);
    if (class_data_off == 0u) {
      continue;
    }
    if (class_data_off < layout.data_off ||
        class_data_off >= layout.file_size) {
      *error = "class_data_out_of_bounds";
      return false;
    }
    size_t cursor = class_data_off;
    std::array<uint32_t, 4> counts{};
    for (uint32_t &count : counts) {
      if (!ReadUleb128(data, &cursor, layout.file_size, &count, error)) {
        return false;
      }
      if (count > layout.file_size) {
        *error = "class_data_count_out_of_bounds";
        return false;
      }
    }
    for (size_t list = 0u; list < 2u; ++list) {
      for (uint32_t index = 0u; index < counts[list]; ++index) {
        uint32_t ignored = 0u;
        if (!ReadUleb128(data, &cursor, layout.file_size, &ignored, error) ||
            !ReadUleb128(data, &cursor, layout.file_size, &ignored, error)) {
          return false;
        }
      }
    }
    for (size_t list = 2u; list < 4u; ++list) {
      uint32_t method_index = 0u;
      for (uint32_t index = 0u; index < counts[list]; ++index) {
        uint32_t delta = 0u;
        uint32_t ignored = 0u;
        uint32_t code_off = 0u;
        if (!ReadUleb128(data, &cursor, layout.file_size, &delta, error) ||
            !ReadUleb128(data, &cursor, layout.file_size, &ignored, error) ||
            !ReadUleb128(data, &cursor, layout.file_size, &code_off, error) ||
            delta > std::numeric_limits<uint32_t>::max() - method_index) {
          if (error->empty()) {
            *error = "method_index_overflow";
          }
          return false;
        }
        method_index += delta;
        if (method_index >= method_count) {
          *error = "method_index_out_of_bounds";
          return false;
        }
        if (code_off != 0u) {
          if (code_off < layout.data_off || code_off >= layout.file_size) {
            *error = "code_item_offset_out_of_bounds";
            return false;
          }
          const auto value = std::make_pair(code_off, class_index);
          const auto [found, inserted] = entries->emplace(method_index, value);
          if (!inserted && found->second != value) {
            *error = "method_code_offset_conflict";
            return false;
          }
        }
      }
    }
  }
  return true;
}

std::optional<std::string> DexString(const std::vector<uint8_t> &data,
                                     const DexLayout &layout,
                                     uint32_t string_index,
                                     std::string *error) {
  const uint32_t string_count = ReadU32(data, 56u);
  const uint32_t string_off = ReadU32(data, 60u);
  if (string_index >= string_count) {
    *error = "string_index_out_of_bounds";
    return std::nullopt;
  }
  const uint32_t data_offset =
      ReadU32(data, static_cast<size_t>(string_off) + string_index * 4u);
  if (data_offset < layout.data_off || data_offset >= layout.file_size) {
    *error = "string_data_out_of_bounds";
    return std::nullopt;
  }
  size_t cursor = data_offset;
  uint32_t ignored = 0u;
  if (!ReadUleb128(data, &cursor, layout.file_size, &ignored, error)) {
    return std::nullopt;
  }
  size_t terminator = cursor;
  while (terminator < layout.file_size && data[terminator] != 0u) {
    ++terminator;
  }
  if (terminator == layout.file_size) {
    *error = "unterminated_string_data";
    return std::nullopt;
  }
  return std::string(reinterpret_cast<const char *>(data.data() + cursor),
                     terminator - cursor);
}

std::optional<std::string> TypeDescriptor(const std::vector<uint8_t> &data,
                                          const DexLayout &layout,
                                          uint32_t type_index,
                                          std::string *error) {
  const uint32_t type_count = ReadU32(data, 64u);
  const uint32_t type_off = ReadU32(data, 68u);
  if (type_index >= type_count) {
    *error = "type_index_out_of_bounds";
    return std::nullopt;
  }
  return DexString(
      data, layout,
      ReadU32(data, static_cast<size_t>(type_off) + type_index * 4u), error);
}

std::optional<MethodCatalog>
BuildMethodCatalog(const std::vector<uint8_t> &data, const DexLayout &layout,
                   std::string *error) {
  const uint32_t method_count = ReadU32(data, 88u);
  const uint32_t method_off = ReadU32(data, 92u);
  const uint32_t proto_count = ReadU32(data, 72u);
  const uint32_t proto_off = ReadU32(data, 76u);
  std::map<uint32_t, std::pair<uint32_t, uint32_t>> code_entries;
  if (!MethodCodeEntries(data, layout, &code_entries, error)) {
    return std::nullopt;
  }
  MethodCatalog catalog;
  for (const auto &[method_index, code_entry] : code_entries) {
    if (method_index >= method_count) {
      *error = "method_index_out_of_bounds";
      return std::nullopt;
    }
    const size_t method = static_cast<size_t>(method_off) + method_index * 8u;
    const uint16_t class_index = ReadU16(data, method);
    const uint16_t proto_index = ReadU16(data, method + 2u);
    const uint32_t name_index = ReadU32(data, method + 4u);
    if (proto_index >= proto_count) {
      *error = "proto_index_out_of_bounds";
      return std::nullopt;
    }
    const size_t proto = static_cast<size_t>(proto_off) + proto_index * 12u;
    const uint32_t return_type_index = ReadU32(data, proto + 4u);
    const uint32_t parameters_off = ReadU32(data, proto + 8u);
    std::string descriptor = "(";
    if (parameters_off != 0u) {
      if (!CheckRange(parameters_off, 4u, layout.file_size)) {
        *error = "parameter_list_out_of_bounds";
        return std::nullopt;
      }
      const uint32_t parameter_count = ReadU32(data, parameters_off);
      if (parameter_count > (layout.file_size - parameters_off - 4u) / 2u) {
        *error = "parameter_list_out_of_bounds";
        return std::nullopt;
      }
      for (uint32_t index = 0u; index < parameter_count; ++index) {
        auto type =
            TypeDescriptor(data, layout,
                           ReadU16(data, static_cast<size_t>(parameters_off) +
                                             4u + index * 2u),
                           error);
        if (!type.has_value()) {
          return std::nullopt;
        }
        descriptor += *type;
      }
    }
    descriptor += ")";
    auto return_type = TypeDescriptor(data, layout, return_type_index, error);
    auto class_name = TypeDescriptor(data, layout, class_index, error);
    auto method_name = DexString(data, layout, name_index, error);
    if (!return_type.has_value() || !class_name.has_value() ||
        !method_name.has_value()) {
      return std::nullopt;
    }
    descriptor += *return_type;
    catalog.emplace(method_index,
                    MethodInfo{*class_name, *method_name, descriptor,
                               code_entry.second, code_entry.first});
  }
  return catalog;
}

std::string NormalizeClassName(const std::string &value) {
  std::string body = value;
  if (body.size() >= 2u && body.front() == 'L' && body.back() == ';') {
    body = body.substr(1u, body.size() - 2u);
  }
  std::replace(body.begin(), body.end(), '.', '/');
  return "L" + body + ";";
}

std::optional<uint32_t> MatchCodeItem(const Json::Value &record,
                                      const MethodCatalog &catalog) {
  if (!record["method_index"].isUInt()) {
    return std::nullopt;
  }
  const uint32_t method_index = record["method_index"].asUInt();
  const auto candidate = catalog.find(method_index);
  if (candidate == catalog.end() || !record["class_name"].isString() ||
      !record["method_name"].isString() ||
      !record["method_descriptor"].isString()) {
    return std::nullopt;
  }
  const MethodInfo &method = candidate->second;
  if (NormalizeClassName(record["class_name"].asString()) !=
          method.class_name ||
      record["method_name"].asString() != method.method_name ||
      record["method_descriptor"].asString() != method.descriptor) {
    return std::nullopt;
  }
  if (record["class_def_index"].isUInt() &&
      record["class_def_index"].asUInt() != method.class_def_index) {
    return std::nullopt;
  }
  return method.code_off;
}

std::optional<size_t> CodeItemSize(const std::vector<uint8_t> &data,
                                   size_t offset, size_t limit,
                                   std::string *error) {
  if (!CheckRange(offset, 16u, limit)) {
    *error = "code_item_header_out_of_bounds";
    return std::nullopt;
  }
  const uint16_t tries_size = ReadU16(data, offset + 6u);
  const uint32_t insns_size = ReadU32(data, offset + 12u);
  size_t cursor = offset + 16u;
  if (insns_size > (limit - cursor) / 2u) {
    *error = "code_item_instructions_out_of_bounds";
    return std::nullopt;
  }
  cursor += static_cast<size_t>(insns_size) * 2u;
  if (tries_size == 0u) {
    return cursor - offset;
  }
  if ((insns_size & 1u) != 0u) {
    if (!CheckRange(cursor, 2u, limit)) {
      *error = "code_item_padding_out_of_bounds";
      return std::nullopt;
    }
    cursor += 2u;
  }
  if (tries_size > (limit - cursor) / 8u) {
    *error = "code_item_tries_out_of_bounds";
    return std::nullopt;
  }
  cursor += static_cast<size_t>(tries_size) * 8u;
  uint32_t handler_count = 0u;
  if (!ReadUleb128(data, &cursor, limit, &handler_count, error) ||
      handler_count > limit - cursor) {
    if (error->empty()) {
      *error = "code_item_handler_count_out_of_bounds";
    }
    return std::nullopt;
  }
  for (uint32_t index = 0u; index < handler_count; ++index) {
    int32_t handler_size = 0;
    if (!ReadSleb128(data, &cursor, limit, &handler_size, error)) {
      return std::nullopt;
    }
    const uint32_t typed_count =
        handler_size < 0
            ? static_cast<uint32_t>(-static_cast<int64_t>(handler_size))
            : static_cast<uint32_t>(handler_size);
    for (uint32_t entry = 0u; entry < typed_count; ++entry) {
      uint32_t ignored = 0u;
      if (!ReadUleb128(data, &cursor, limit, &ignored, error) ||
          !ReadUleb128(data, &cursor, limit, &ignored, error)) {
        return std::nullopt;
      }
    }
    if (handler_size <= 0) {
      uint32_t ignored = 0u;
      if (!ReadUleb128(data, &cursor, limit, &ignored, error)) {
        return std::nullopt;
      }
    }
  }
  return cursor - offset;
}

std::string RecordString(const Json::Value &record, const char *name) {
  return record[name].isString() ? record[name].asString() : std::string();
}

bool IsCodeItem(const Json::Value &record) {
  return RecordString(record, "capture_kind") == "codeitem";
}

std::optional<fs::path> ArtifactPath(const fs::path &process,
                                     const Json::Value &record) {
  if (!record["artifact_relative_path"].isString()) {
    return std::nullopt;
  }
  const std::string relative = record["artifact_relative_path"].asString();
  if (!SafeRelativePath(relative)) {
    return std::nullopt;
  }
  const fs::path path = process / fs::path(relative);
  std::error_code error;
  const fs::file_status status = fs::symlink_status(path, error);
  if (error || !fs::is_regular_file(status) || fs::is_symlink(status)) {
    return std::nullopt;
  }
  return path;
}

std::optional<std::string> MethodIdentity(const Json::Value &record) {
  if (!IsCodeItem(record) || !record["method_index"].isUInt()) {
    return std::nullopt;
  }
  const std::string class_name = RecordString(record, "class_name");
  const std::string method_name = RecordString(record, "method_name");
  const std::string descriptor = RecordString(record, "method_descriptor");
  if (!class_name.empty() && !method_name.empty() && !descriptor.empty()) {
    return NormalizeClassName(class_name) + "\n" + method_name + "\n" +
           descriptor + "\n" + std::to_string(record["method_index"].asUInt());
  }
  if (!record["dex_object_id"].isNull()) {
    return record["dex_object_id"].asString() + "\n" +
           std::to_string(record["method_index"].asUInt());
  }
  return std::nullopt;
}

std::optional<std::string> ExecutionPathIdentity(const Json::Value &record) {
  if (record["dex_object_id"].isNull() || !record["method_index"].isUInt()) {
    return std::nullopt;
  }
  std::string key = record["dex_object_id"].asString() + "\n" +
                    std::to_string(record["method_index"].asUInt()) + "\n";
  if (record["active_attempt_id"].isIntegral()) {
    key += record["active_attempt_id"].asString();
  } else {
    key += "null";
  }
  return key;
}

std::vector<fs::path> SortedJsonRecords(const fs::path &records) {
  std::vector<fs::path> paths;
  std::error_code error;
  if (!fs::is_directory(records, error)) {
    return paths;
  }
  for (const fs::directory_entry &entry :
       fs::directory_iterator(records, error)) {
    if (error) {
      break;
    }
    if (entry.path().extension() == ".json" && entry.is_regular_file(error) &&
        !entry.is_symlink(error)) {
      paths.push_back(entry.path());
    }
  }
  std::sort(paths.begin(), paths.end());
  return paths;
}

struct Discovered {
  std::string name;
  Json::Value record;
  std::optional<fs::path> artifact;
  std::string relative_artifact;
  std::string digest;
};

Json::Value BaseRecordItem(const Discovered &entry) {
  Json::Value item(Json::objectValue);
  item["algorithm_version"] = kAlgorithmVersion;
  item["record"] = entry.name;
  if (!entry.digest.empty()) {
    item["input_sha256"] = entry.digest;
  }
  if (!entry.relative_artifact.empty()) {
    item["artifact"] = entry.relative_artifact;
  }
  item["site"] = entry.record["site"];
  item["capture_point"] = entry.record["capture_point_name"];
  item["conflicts"] = Json::Value(Json::arrayValue);
  return item;
}

ProcessSummary RepairProcess(const fs::path &process, const fs::path &output) {
  ProcessSummary summary;
  std::error_code error;
  fs::create_directories(output / "blobs", error);
  fs::create_directories(output / "repaired", error);
  fs::create_directories(output / "partial", error);
  chmod(output.c_str(), kDirectoryMode);
  chmod((output / "blobs").c_str(), kDirectoryMode);
  chmod((output / "repaired").c_str(), kDirectoryMode);
  chmod((output / "partial").c_str(), kDirectoryMode);

  std::vector<Discovered> discovered;
  std::map<std::string, std::set<std::string>> method_hashes;
  for (const fs::path &record_path : SortedJsonRecords(process / "records")) {
    Json::Value record;
    if (!ReadJson(record_path, &record)) {
      continue;
    }
    Discovered entry;
    entry.name = record_path.filename().string();
    entry.record = std::move(record);
    entry.artifact = ArtifactPath(process, entry.record);
    if (entry.artifact.has_value()) {
      std::vector<uint8_t> data;
      if (ReadFile(*entry.artifact, &data)) {
        entry.relative_artifact =
            fs::relative(*entry.artifact, process, error).generic_string();
        if (error) {
          entry.relative_artifact.clear();
          error.clear();
        }
        entry.digest = Sha256(data);
        if (auto identity = MethodIdentity(entry.record);
            identity.has_value()) {
          method_hashes[*identity].insert(entry.digest);
        }
      } else {
        entry.artifact.reset();
      }
    }
    discovered.push_back(std::move(entry));
  }

  std::vector<DexCandidate> candidates;
  std::set<std::string> candidate_digests;
  for (const Discovered &entry : discovered) {
    if (!entry.artifact.has_value() || IsCodeItem(entry.record) ||
        entry.digest.empty() || candidate_digests.count(entry.digest) != 0u) {
      continue;
    }
    std::vector<uint8_t> data;
    if (!ReadFile(*entry.artifact, &data)) {
      continue;
    }
    const Validation validation = ValidateDex(data);
    if (!validation.layout.has_value()) {
      continue;
    }
    std::string catalog_error;
    auto catalog = BuildMethodCatalog(data, *validation.layout, &catalog_error);
    if (!catalog.has_value()) {
      continue;
    }
    candidates.push_back(DexCandidate{*entry.artifact, entry.relative_artifact,
                                      entry.digest, std::move(*catalog)});
    candidate_digests.insert(entry.digest);
  }

  std::map<std::string, Json::Value> items;
  std::map<std::string, Json::Value> inputs;
  std::set<std::string> seen_blobs;
  std::vector<const Discovered *> code_items;
  for (const Discovered &entry : discovered) {
    Json::Value item = BaseRecordItem(entry);
    if (!entry.artifact.has_value() || entry.digest.empty()) {
      item["trust"] = "partial";
      if (RecordString(entry.record, "status") == "dropped") {
        item["reason"] = "capture_dropped";
        item["drop_reason"] = entry.record["drop_reason"];
      } else {
        item["reason"] = "missing_artifact";
      }
      items[entry.name] = std::move(item);
      continue;
    }
    std::vector<uint8_t> data;
    if (!ReadFile(*entry.artifact, &data)) {
      item["trust"] = "partial";
      item["reason"] = "missing_artifact";
      items[entry.name] = std::move(item);
      continue;
    }
    if (seen_blobs.insert(entry.digest).second) {
      WriteFile(output / "blobs" / (entry.digest + ".bin"), data);
    }
    Json::Value &input = inputs[entry.digest];
    if (input.isNull()) {
      input = Json::Value(Json::objectValue);
      input["sha256"] = entry.digest;
      input["size"] = Json::UInt64(data.size());
      input["artifact"] = entry.relative_artifact;
      input["records"] = Json::Value(Json::arrayValue);
    }
    input["records"].append(entry.name);

    if (auto identity = MethodIdentity(entry.record);
        identity.has_value() && method_hashes[*identity].size() > 1u) {
      Json::Value conflict(Json::objectValue);
      conflict["type"] = "code_item_bytes";
      Json::Value hashes(Json::arrayValue);
      for (const std::string &digest : method_hashes[*identity]) {
        hashes.append(digest);
      }
      conflict["candidate_sha256"] = std::move(hashes);
      item["conflicts"].append(std::move(conflict));
      item["trust"] = "partial";
      item["reason"] = "code_item_conflict";
    }

    if (IsCodeItem(entry.record)) {
      if (!item.isMember("trust")) {
        item["trust"] = "partial";
        item["kind"] = "codeitem";
        item["output_kind"] = "dexrecon";
        item["reason"] = "method_evidence_only";
      }
      items[entry.name] = std::move(item);
      code_items.push_back(&entry);
      continue;
    }

    if (RecordString(entry.record, "capture_kind") == "dexdata") {
      item["kind"] = "dexdata";
      if (auto rebuilt = RehydrateDexData(data, entry.record);
          rebuilt.has_value()) {
        const fs::path rehydrated =
            output / "repaired" / (entry.digest + ".rehydrated.dex");
        if (WriteFile(rehydrated, *rebuilt)) {
          item["trust"] = "verified";
          item["output_kind"] = "reconstructed_standard";
          item["file_size"] = Json::UInt64(rebuilt->size());
          item["output"] =
              fs::relative(rehydrated, output, error).generic_string();
          items[entry.name] = std::move(item);
          continue;
        }
      }
      item["trust"] = "partial";
      item["output_kind"] = "dexrecon";
      item["reason"] = "dexrecon_missing_runtime_tables";
      const fs::path partial =
          output / "partial" / (entry.digest + ".partial.json");
      WriteText(partial, JsonText(item));
      item["output"] = fs::relative(partial, output, error).generic_string();
      items[entry.name] = std::move(item);
      continue;
    }

    auto [repaired, details] = RepairDexFixed(data);
    for (const std::string &name : details.getMemberNames()) {
      item[name] = details[name];
    }
    if (repaired.has_value()) {
      const fs::path fixed =
          output / "repaired" / (entry.digest + ".dexfixed.dex");
      WriteFile(fixed, *repaired);
      item["output"] = fs::relative(fixed, output, error).generic_string();
      items[entry.name] = std::move(item);
      continue;
    }
    // A "dex" capture whose header spans a container (its declared file_size
    // exceeds the single-DEX extent, so RepairDexFixed reports
    // data_range_out_of_bounds) still carries a RuntimeView. Rebuild it through
    // the rehydrator, exactly as a dexdata capture, before giving up as partial.
    if (auto rebuilt = RehydrateDexData(data, entry.record);
        rebuilt.has_value()) {
      const fs::path rehydrated =
          output / "repaired" / (entry.digest + ".rehydrated.dex");
      if (WriteFile(rehydrated, *rebuilt)) {
        item.removeMember("reason");
        item["trust"] = "verified";
        item["output_kind"] = "reconstructed_standard";
        item["file_size"] = Json::UInt64(rebuilt->size());
        item["output"] = fs::relative(rehydrated, output, error).generic_string();
        items[entry.name] = std::move(item);
        continue;
      }
    }
    const fs::path partial =
        output / "partial" / (entry.digest + ".partial.json");
    WriteText(partial, JsonText(item));
    item["output"] = fs::relative(partial, output, error).generic_string();
    items[entry.name] = std::move(item);
  }

  struct Patch {
    const Discovered *entry;
    uint32_t target_offset;
  };
  std::map<std::string, std::vector<Patch>> patch_groups;
  for (const Discovered *entry : code_items) {
    Json::Value &item = items[entry->name];
    if (RecordString(item, "reason") == "code_item_conflict") {
      continue;
    }
    std::vector<std::pair<std::string, uint32_t>> matches;
    for (const DexCandidate &candidate : candidates) {
      if (auto offset = MatchCodeItem(entry->record, candidate.catalog);
          offset.has_value()) {
        matches.emplace_back(candidate.digest, *offset);
      }
    }
    if (matches.empty()) {
      item["reason"] = "target_dex_not_found";
      continue;
    }
    if (matches.size() != 1u) {
      item["reason"] = "target_dex_ambiguous";
      Json::Value digests(Json::arrayValue);
      for (const auto &[digest, ignored] : matches) {
        static_cast<void>(ignored);
        digests.append(digest);
      }
      item["candidate_dex_sha256"] = std::move(digests);
      continue;
    }
    patch_groups[matches.front().first].push_back(
        Patch{entry, matches.front().second});
  }

  Json::Value reconstructions(Json::arrayValue);
  for (const auto &group : patch_groups) {
    // Bind the key explicitly: a structured binding may not be captured by a lambda in C++17
    // (that relaxation only arrived in C++20), and this tree compiles with -std=gnu++17.
    const std::string &candidate_digest = group.first;
    const std::vector<Patch> &patches = group.second;
    const auto candidate_it = std::find_if(
        candidates.begin(), candidates.end(), [&candidate_digest](const DexCandidate &value) {
          return value.digest == candidate_digest;
        });
    if (candidate_it == candidates.end()) {
      continue;
    }
    std::vector<uint8_t> base;
    if (!ReadFile(candidate_it->artifact, &base)) {
      continue;
    }
    const Validation validation = ValidateDex(base);
    if (!validation.layout.has_value()) {
      continue;
    }
    std::map<uint32_t,
             std::pair<std::vector<uint8_t>, std::vector<std::string>>>
        accepted;
    std::set<uint32_t> conflicted_offsets;
    Json::Value outcomes(Json::arrayValue);
    for (const Patch &patch : patches) {
      Json::Value outcome(Json::objectValue);
      outcome["record"] = patch.entry->name;
      std::vector<uint8_t> code;
      std::string reason;
      if (!ReadFile(*patch.entry->artifact, &code) || code.empty()) {
        reason = "code_item_bytes_missing";
      } else if (!patch.entry->record["method_index"].isUInt()) {
        reason = "code_item_mapping_missing";
      } else if (patch.entry->record["code_item_size"].isUInt64() &&
                 patch.entry->record["code_item_size"].asUInt64() !=
                     code.size()) {
        reason = "code_item_size_mismatch";
      } else {
        std::string size_error;
        auto source_size =
            CodeItemSize(base, patch.target_offset,
                         validation.layout->file_size, &size_error);
        auto captured_size = CodeItemSize(code, 0u, code.size(), &size_error);
        if (!source_size.has_value() || !captured_size.has_value()) {
          reason = size_error;
        } else if (*source_size != code.size() ||
                   *captured_size != code.size()) {
          reason = "code_item_span_mismatch";
        }
      }
      if (reason.empty() &&
          conflicted_offsets.count(patch.target_offset) != 0u) {
        reason = "code_item_conflict";
      }
      if (reason.empty()) {
        auto previous = accepted.find(patch.target_offset);
        if (previous != accepted.end() && previous->second.first != code) {
          for (const std::string &previous_record : previous->second.second) {
            Json::Value &previous_item = items[previous_record];
            previous_item["trust"] = "partial";
            previous_item["reason"] = "code_item_conflict";
          }
          accepted.erase(previous);
          conflicted_offsets.insert(patch.target_offset);
          reason = "code_item_conflict";
        } else if (previous == accepted.end()) {
          accepted.emplace(
              patch.target_offset,
              std::make_pair(std::move(code),
                             std::vector<std::string>{patch.entry->name}));
        } else {
          previous->second.second.push_back(patch.entry->name);
        }
      }
      if (reason.empty()) {
        outcome["status"] = "applied";
        outcome["method_index"] = patch.entry->record["method_index"].asUInt();
        outcome["offset"] = patch.target_offset;
        outcome["size"] =
            Json::UInt64(accepted[patch.target_offset].first.size());
      } else {
        outcome["status"] = "partial";
        outcome["reason"] = reason;
      }
      outcomes.append(std::move(outcome));
    }

    std::optional<fs::path> output_path;
    std::string output_digest;
    if (!accepted.empty()) {
      std::vector<uint8_t> repaired(
          base.begin(), base.begin() + validation.layout->file_size);
      for (const auto &[offset, patch] : accepted) {
        std::copy(patch.first.begin(), patch.first.end(),
                  repaired.begin() + offset);
      }
      std::array<uint8_t, SHA_DIGEST_LENGTH> signature{};
      SHA1(repaired.data() + 32u, repaired.size() - 32u, signature.data());
      std::copy(signature.begin(), signature.end(), repaired.begin() + 12u);
      WriteU32(&repaired, 8u,
               adler32(adler32(0L, Z_NULL, 0), repaired.data() + 12u,
              repaired.size() - 12u));
      output_path = output / "repaired" / (candidate_digest + ".codeitem.dex");
      if (WriteFile(*output_path, repaired)) {
        output_digest = Sha256(repaired);
      } else {
        output_path.reset();
      }
    }

    Json::Value reconstruction(Json::objectValue);
    reconstruction["base_sha256"] = candidate_digest;
    reconstruction["base_artifact"] = candidate_it->relative_artifact;
    reconstruction["outcomes"] = outcomes;
    if (output_path.has_value()) {
      reconstruction["output"] =
          fs::relative(*output_path, output, error).generic_string();
      reconstruction["output_sha256"] = output_digest;
    } else {
      reconstruction["output"] = Json::nullValue;
    }
    reconstructions.append(reconstruction);

    for (const Json::Value &outcome : outcomes) {
      Json::Value &item = items[outcome["record"].asString()];
      if (RecordString(outcome, "status") == "applied" &&
          output_path.has_value()) {
        item.removeMember("reason");
        item["trust"] = "repaired";
        item["output_kind"] = "codeitem_backfill";
        item["output"] =
            fs::relative(*output_path, output, error).generic_string();
        item["target_dex_sha256"] = candidate_digest;
        item["backfill_offset"] = outcome["offset"];
        item["backfill_size"] = outcome["size"];
      } else {
        item["trust"] = "partial";
        item["reason"] = outcome.isMember("reason")
                             ? outcome["reason"]
                             : Json::Value("backfill_failed");
      }
    }
  }

  for (const Discovered *entry : code_items) {
    Json::Value &item = items[entry->name];
    if (RecordString(item, "trust") != "partial") {
      continue;
    }
    const fs::path partial = output / "partial" /
                             (fs::path(entry->name).stem().string() + "-" +
                              entry->digest + ".partial.json");
    WriteText(partial, JsonText(item));
    item["output"] = fs::relative(partial, output, error).generic_string();
  }

  std::map<std::string, Json::Value> execution_paths;
  std::map<std::string, std::vector<std::string>> execution_hashes;
  std::vector<std::string> execution_path_order;
  for (const Discovered &entry : discovered) {
    const std::string phase = RecordString(entry.record, "observation_phase");
    const std::optional<std::string> identity = ExecutionPathIdentity(entry.record);
    if (phase.empty() || !identity.has_value()) {
      continue;
    }
    auto [found, inserted] = execution_paths.try_emplace(*identity, Json::Value(Json::objectValue));
    Json::Value &path = found->second;
    if (inserted) {
      execution_path_order.push_back(*identity);
      path["dex_object_id"] = entry.record["dex_object_id"];
      path["method_index"] = entry.record["method_index"];
      path["active_attempt_id"] = entry.record["active_attempt_id"];
      const std::string entry_kind = RecordString(entry.record, "entry_kind");
      path["first_entry_kind"] = entry_kind.empty() ? "unknown" : entry_kind;
      path["observed_phases"] = Json::Value(Json::arrayValue);
      path["first_artifact_fast_hash"] = Json::Value(Json::nullValue);
      path["last_artifact_fast_hash"] = Json::Value(Json::nullValue);
      path["code_item_changed"] = "unknown";
    }
    bool phase_seen = false;
    for (const Json::Value &observed : path["observed_phases"]) {
      phase_seen = phase_seen || observed.asString() == phase;
    }
    if (!phase_seen) {
      path["observed_phases"].append(phase);
    }
    const std::string fast_hash = RecordString(entry.record, "artifact_fast_hash");
    if (IsCodeItem(entry.record) && entry.artifact.has_value() && !entry.digest.empty() &&
        !fast_hash.empty()) {
      execution_hashes[*identity].push_back(fast_hash);
    }
  }

  Json::Value method_execution_paths(Json::arrayValue);
  for (const std::string &identity : execution_path_order) {
    Json::Value &path = execution_paths[identity];
    const std::vector<std::string> &hashes = execution_hashes[identity];
    if (!hashes.empty()) {
      path["first_artifact_fast_hash"] = hashes.front();
      path["last_artifact_fast_hash"] = hashes.back();
    }
    if (hashes.size() >= 2u) {
      path["code_item_changed"] = hashes.front() != hashes.back();
    }
    method_execution_paths.append(path);
  }

  Json::Value report(Json::objectValue);
  report["algorithm_version"] = kAlgorithmVersion;
  report["input"] = process.string();
  report["records"] = Json::Value(Json::arrayValue);
  std::set<std::string> usable_outputs;
  for (const auto &[name, item] : items) {
    static_cast<void>(name);
    report["records"].append(item);
    ++summary.record_count;
    const std::string trust = RecordString(item, "trust");
    if (trust == "verified") {
      ++summary.verified;
    } else if (trust == "repaired") {
      ++summary.repaired;
      if (RecordString(item, "output_kind") == "codeitem_backfill") {
        ++summary.codeitems_backfilled;
      }
    } else {
      ++summary.partial;
    }
    if ((trust == "verified" || trust == "repaired") &&
        item["output"].isString()) {
      usable_outputs.insert(item["output"].asString());
    }
  }
  report["record_count"] = summary.record_count;
  report["reconstructions"] = reconstructions;
  report["method_execution_paths"] = method_execution_paths;
  for (const Json::Value &reconstruction : reconstructions) {
    if (reconstruction["output"].isString()) {
      usable_outputs.insert(reconstruction["output"].asString());
    }
  }
  summary.usable_outputs = usable_outputs.size();

  Json::Value input_report(Json::objectValue);
  input_report["run"] = process.string();
  input_report["blobs"] = Json::Value(Json::arrayValue);
  for (const auto &[digest, input] : inputs) {
    static_cast<void>(digest);
    input_report["blobs"].append(input);
  }
  WriteText(output / "inputs.json", JsonText(input_report));
  WriteText(output / "report.json", JsonText(report));
  summary.report = std::move(report);
  return summary;
}

// Count captured artifacts across the run's process-* directories and report the
// newest artifact mtime. Shared by the configured-run and latest-run selectors.
bool ScanRunArtifacts(const fs::path &run_path, uint32_t *artifact_count,
                      fs::file_time_type *newest) {
  std::error_code error;
  uint32_t count = 0u;
  fs::file_time_type latest = fs::file_time_type::min();
  for (const fs::directory_entry &process :
       fs::directory_iterator(run_path, error)) {
    if (error || !process.is_directory(error) || process.is_symlink(error) ||
        process.path().filename().string().rfind("process-", 0u) != 0u ||
        !fs::is_regular_file(process.path() / "session.json", error)) {
      error.clear();
      continue;
    }
    const fs::path artifacts = process.path() / "artifacts";
    if (!fs::is_directory(artifacts, error)) {
      error.clear();
      continue;
    }
    for (const fs::directory_entry &artifact :
         fs::directory_iterator(artifacts, error)) {
      if (error || !artifact.is_regular_file(error) ||
          artifact.is_symlink(error)) {
        error.clear();
        continue;
      }
      ++count;
      const fs::file_time_type modified = artifact.last_write_time(error);
      if (!error && modified > latest) {
        latest = modified;
      }
      error.clear();
    }
  }
  *artifact_count = count;
  *newest = latest;
  return count != 0u;
}

std::optional<RunCandidate> FindLatestRun(const fs::path &runs) {
  std::error_code error;
  if (!fs::is_directory(runs, error)) {
    return std::nullopt;
  }
  std::optional<RunCandidate> selected;
  for (const fs::directory_entry &run : fs::directory_iterator(runs, error)) {
    if (error || !run.is_directory(error) || run.is_symlink(error) ||
        !IsValidRunId(run.path().filename().string())) {
      error.clear();
      continue;
    }
    uint32_t artifact_count = 0u;
    fs::file_time_type newest = fs::file_time_type::min();
    if (!ScanRunArtifacts(run.path(), &artifact_count, &newest)) {
      continue;
    }
    RunCandidate candidate{run.path(), run.path().filename().string(), newest,
                           artifact_count};
    if (!selected.has_value() ||
        std::tie(candidate.newest, candidate.run_id) >
            std::tie(selected->newest, selected->run_id)) {
      selected = std::move(candidate);
    }
  }
  return selected;
}

// Prefer the run the Manager most recently configured
// (persist.sys.r0dump.fusion.run_id) so export targets the capture the operator
// just made, rather than guessing by artifact mtime -- which is unreliable across
// sessions and a skewed device clock, and in practice selected a stale earlier run.
// Returns nullopt (falling back to FindLatestRun) when the property is unset or its
// run directory holds no artifacts.
std::optional<RunCandidate> FindConfiguredRun(const fs::path &runs) {
#if defined(__ANDROID__)
  char value[PROP_VALUE_MAX] = {};
  if (__system_property_get("persist.sys.r0dump.fusion.run_id", value) <= 0) {
    return std::nullopt;
  }
  const std::string run_id(value);
  if (run_id.empty() || !IsValidRunId(run_id)) {
    return std::nullopt;
  }
  std::error_code error;
  const fs::path run_path = runs / run_id;
  if (!fs::is_directory(run_path, error) || fs::is_symlink(run_path, error)) {
    return std::nullopt;
  }
  uint32_t artifact_count = 0u;
  fs::file_time_type newest = fs::file_time_type::min();
  if (!ScanRunArtifacts(run_path, &artifact_count, &newest)) {
    return std::nullopt;
  }
  return RunCandidate{run_path, run_id, newest, artifact_count};
#else
  (void)runs;
  return std::nullopt;
#endif
}

bool CopyTree(const fs::path &source, const fs::path &destination,
              std::string *detail) {
  std::error_code error;
  fs::create_directories(destination, error);
  if (error) {
    *detail = "create_staging_failed";
    return false;
  }
  chmod(destination.c_str(), kDirectoryMode);
  uint64_t total_bytes = 0u;
  uint32_t file_count = 0u;
  for (const fs::directory_entry &entry :
       fs::recursive_directory_iterator(source, error)) {
    if (error) {
      *detail = "source_walk_failed";
      return false;
    }
    const fs::file_status status = entry.symlink_status(error);
    if (error || fs::is_symlink(status)) {
      *detail = "source_symlink_rejected";
      return false;
    }
    const fs::path relative = fs::relative(entry.path(), source, error);
    if (error || relative.empty()) {
      *detail = "source_relative_path_failed";
      return false;
    }
    const fs::path target = destination / relative;
    if (fs::is_directory(status)) {
      fs::create_directory(target, error);
      if (error && error != std::errc::file_exists) {
        *detail = "create_staging_directory_failed";
        return false;
      }
      error.clear();
      chmod(target.c_str(), kDirectoryMode);
      continue;
    }
    if (!fs::is_regular_file(status)) {
      *detail = "source_entry_type_rejected";
      return false;
    }
    const uint64_t size = entry.file_size(error);
    if (error || size > kMaxSingleFileBytes ||
        size > kMaxExportBytes - total_bytes ||
        ++file_count > kMaxExportFiles) {
      *detail = "source_limits_exceeded";
      return false;
    }
    total_bytes += size;
    fs::copy_file(entry.path(), target, fs::copy_options::overwrite_existing,
                  error);
    if (error) {
      *detail = "copy_source_failed";
      return false;
    }
    chmod(target.c_str(), kFileMode);
  }
  return true;
}

bool CopyFdToFile(int fd, const fs::path &target) {
  if (fd < 0 || lseek(fd, 0, SEEK_SET) < 0) {
    return false;
  }
  const int output =
      open(target.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, kFileMode);
  if (output < 0) {
    return false;
  }
  bool ok = true;
  uint64_t total = 0u;
  std::array<uint8_t, 64 * 1024> buffer{};
  for (;;) {
    const ssize_t count =
        TEMP_FAILURE_RETRY(read(fd, buffer.data(), buffer.size()));
    if (count == 0) {
      break;
    }
    if (count < 0 ||
        static_cast<uint64_t>(count) > kMaxSingleFileBytes - total) {
      ok = false;
      break;
    }
    total += static_cast<uint64_t>(count);
    size_t offset = 0u;
    while (offset < static_cast<size_t>(count)) {
      const ssize_t written = TEMP_FAILURE_RETRY(write(
          output, buffer.data() + offset, static_cast<size_t>(count) - offset));
      if (written <= 0) {
        ok = false;
        break;
      }
      offset += static_cast<size_t>(written);
    }
    if (!ok) {
      break;
    }
  }
  if (fsync(output) != 0) {
    ok = false;
  }
  close(output);
  if (!ok) {
    unlink(target.c_str());
  }
  return ok;
}

bool ExtractManifest(const fs::path &apk, const fs::path &target) {
  ZipArchiveHandle archive = nullptr;
  if (OpenArchive(apk.c_str(), &archive) != 0) {
    return false;
  }
  ZipEntry entry;
  bool ok = FindEntry(archive, "AndroidManifest.xml", &entry) == 0 &&
            entry.uncompressed_length <= kMaxSingleFileBytes;
  std::vector<uint8_t> manifest;
  if (ok) {
    manifest.resize(entry.uncompressed_length);
    ok =
        ExtractToMemory(archive, &entry, manifest.data(), manifest.size()) == 0;
  }
  CloseArchive(archive);
  return ok && WriteFile(target, manifest);
}

bool AddFileToZip(ZipWriter *writer, const fs::path &file,
                  const std::string &entry_name) {
  // This tree's libziparchive only exposes kCompress and kAlign32; the
  // kDefaultCompression flag was added later.  Storing with deflate is what the
  // export format expects, and kCompress selects exactly that.
  if (writer->StartEntry(entry_name, ZipWriter::kCompress) != 0) {
    return false;
  }
  std::ifstream input(file, std::ios::binary);
  std::array<char, 64 * 1024> buffer{};
  while (input) {
    input.read(buffer.data(), buffer.size());
    const std::streamsize count = input.gcount();
    if (count > 0 &&
        writer->WriteBytes(buffer.data(), static_cast<size_t>(count)) != 0) {
      return false;
    }
  }
  return (input.eof() || input.good()) && writer->FinishEntry() == 0;
}

bool CreateZip(const fs::path &source, const fs::path &archive,
               const std::string &prefix) {
  FILE *file = fopen(archive.c_str(), "wb");
  if (file == nullptr) {
    return false;
  }
  ZipWriter writer(file);
  bool ok = true;
  std::error_code error;
  std::vector<fs::path> files;
  for (const fs::directory_entry &entry :
       fs::recursive_directory_iterator(source, error)) {
    if (error || entry.is_symlink(error)) {
      ok = false;
      break;
    }
    if (entry.is_regular_file(error)) {
      files.push_back(entry.path());
    }
  }
  std::sort(files.begin(), files.end());
  for (const fs::path &path : files) {
    const std::string name =
        prefix + "/" + fs::relative(path, source, error).generic_string();
    if (error || !AddFileToZip(&writer, path, name)) {
      ok = false;
      break;
    }
  }
  if (ok && writer.Finish() != 0) {
    ok = false;
  }
  if (fclose(file) != 0) {
    ok = false;
  }
  if (!ok) {
    unlink(archive.c_str());
  } else {
    chmod(archive.c_str(), kFileMode);
  }
  return ok;
}

std::string PublicPath(const fs::path &path) {
  const std::string native = path.string();
  constexpr std::string_view root = "/mnt/user/0/emulated/0";
  if (native.rfind(root, 0u) == 0u) {
    return "/sdcard" + native.substr(root.size());
  }
  return native;
}

bool CopyApksAndManifests(const std::vector<int> &apk_fds,
                          const fs::path &staging, std::string *detail) {
  std::error_code error;
  const fs::path apk_directory = staging / "apk";
  const fs::path manifest_directory = staging / "manifest";
  fs::create_directories(apk_directory, error);
  fs::create_directories(manifest_directory, error);
  if (error) {
    *detail = "create_apk_directory_failed";
    return false;
  }
  chmod(apk_directory.c_str(), kDirectoryMode);
  chmod(manifest_directory.c_str(), kDirectoryMode);
  for (size_t index = 0u; index < apk_fds.size(); ++index) {
    const std::string stem =
        index == 0u ? "base" : "split-" + std::to_string(index);
    const fs::path apk = apk_directory / (stem + ".apk");
    if (!CopyFdToFile(apk_fds[index], apk)) {
      *detail = "copy_apk_failed";
      return false;
    }
    if (!ExtractManifest(apk,
                         manifest_directory /
                             (index == 0u ? "AndroidManifest.xml"
                                          : stem + "-AndroidManifest.xml"))) {
      *detail = "extract_manifest_failed";
      return false;
    }
  }
  Json::Value index(Json::objectValue);
  index["base_apk"] = "apk/base.apk";
  index["base_manifest"] = "manifest/AndroidManifest.xml";
  index["split_count"] = static_cast<Json::UInt>(apk_fds.size() - 1u);
  return WriteText(staging / "jadx-input.json", JsonText(index));
}

} // namespace

Status ExportLatest(const fs::path &storage_root,
                    const fs::path &downloads_root,
                    std::string_view package_name,
                    const std::vector<int> &apk_fds, ExportSummary *summary) {
  if (summary == nullptr || !IsValidPackageName(package_name) ||
      apk_fds.empty() || apk_fds.size() > 32u) {
    return Status::kProtocolError;
  }
  *summary = ExportSummary{};
  const fs::path runs = storage_root / "Android" / "data" /
                        std::string(package_name) / "files" / "r0dump-fusion" /
                        "runs";
  auto candidate = FindConfiguredRun(runs);
  if (!candidate.has_value()) {
    candidate = FindLatestRun(runs);
  }
  if (!candidate.has_value()) {
    summary->detail = "no_run_with_artifacts";
    return Status::kNoRuns;
  }
  summary->run_id = candidate->run_id;
  summary->remote_artifact_count = candidate->artifact_count;

  std::error_code error;
  const fs::path package_output =
      downloads_root / "r0dump-exports" / std::string(package_name);
  fs::create_directories(package_output, error);
  if (error) {
    summary->detail = "downloads_unavailable";
    return Status::kStorageUnavailable;
  }
  chmod((downloads_root / "r0dump-exports").c_str(), kDirectoryMode);
  chmod(package_output.c_str(), kDirectoryMode);

  const std::string unique =
      "." + candidate->run_id + ".partial-" + std::to_string(getpid()) + "-" +
      std::to_string(
          std::chrono::steady_clock::now().time_since_epoch().count());
  const fs::path staging = package_output / unique;
  if (!CopyTree(candidate->path, staging, &summary->detail)) {
    fs::remove_all(staging, error);
    return Status::kRepairFailed;
  }
  if (!CopyApksAndManifests(apk_fds, staging, &summary->detail)) {
    fs::remove_all(staging, error);
    return Status::kRepairFailed;
  }

  Json::Value process_reports(Json::arrayValue);
  std::vector<fs::path> processes;
  for (const fs::directory_entry &entry :
       fs::directory_iterator(staging, error)) {
    if (!error && entry.is_directory(error) && !entry.is_symlink(error) &&
        entry.path().filename().string().rfind("process-", 0u) == 0u) {
      processes.push_back(entry.path());
    }
    error.clear();
  }
  std::sort(processes.begin(), processes.end());
  for (const fs::path &process : processes) {
    const fs::path repair = process / "host-repair";
    const ProcessSummary result = RepairProcess(process, repair);
    summary->record_count += result.record_count;
    summary->verified += result.verified;
    summary->repaired += result.repaired;
    summary->partial += result.partial;
    summary->usable_outputs += result.usable_outputs;
    summary->codeitems_backfilled += result.codeitems_backfilled;
    Json::Value item(Json::objectValue);
    item["process"] = process.filename().string();
    item["status"] = "ok";
    item["algorithm_version"] = kAlgorithmVersion;
    item["record_count"] = result.record_count;
    item["verified"] = result.verified;
    item["repaired"] = result.repaired;
    item["partial"] = result.partial;
    item["usable_outputs"] = result.usable_outputs;
    item["codeitems_backfilled"] = result.codeitems_backfilled;
    item["report"] = (process.filename() / fs::path("host-repair/report.json"))
                         .generic_string();
    process_reports.append(std::move(item));
  }
  if (processes.empty()) {
    summary->detail = "no_process_directories";
    fs::remove_all(staging, error);
    return Status::kRepairFailed;
  }

  Json::Value export_report(Json::objectValue);
  export_report["schema"] = 1;
  export_report["algorithm_version"] = kAlgorithmVersion;
  export_report["package"] = std::string(package_name);
  export_report["run_id"] = candidate->run_id;
  export_report["remote_artifact_count"] = candidate->artifact_count;
  export_report["apk_count"] = static_cast<Json::UInt>(apk_fds.size());
  export_report["manifest_included"] = true;
  export_report["process_reports"] = process_reports;
  if (!WriteText(staging / "export-report.json", JsonText(export_report))) {
    summary->detail = "write_export_report_failed";
    fs::remove_all(staging, error);
    return Status::kRepairFailed;
  }

  const fs::path temporary_zip =
      package_output / ("." + candidate->run_id + ".zip.partial");
  const fs::path final_zip = package_output / (candidate->run_id + ".zip");
  const fs::path temporary_sha =
      package_output / ("." + candidate->run_id + ".zip.sha256.partial");
  const fs::path final_sha =
      package_output / (candidate->run_id + ".zip.sha256");
  unlink(temporary_zip.c_str());
  unlink(temporary_sha.c_str());
  if (!CreateZip(staging, temporary_zip,
                 std::string(package_name) + "/" + candidate->run_id)) {
    summary->detail = "create_archive_failed";
    fs::remove_all(staging, error);
    return Status::kArchiveFailed;
  }
  summary->sha256 = Sha256File(temporary_zip);
  if (summary->sha256.empty() ||
      !WriteText(temporary_sha, summary->sha256 + "  " +
                                    final_zip.filename().string() + "\n")) {
    summary->detail = "create_checksum_failed";
    unlink(temporary_zip.c_str());
    unlink(temporary_sha.c_str());
    fs::remove_all(staging, error);
    return Status::kArchiveFailed;
  }
  unlink(final_zip.c_str());
  unlink(final_sha.c_str());
  if (rename(temporary_zip.c_str(), final_zip.c_str()) != 0 ||
      rename(temporary_sha.c_str(), final_sha.c_str()) != 0) {
    summary->detail = "publish_archive_failed";
    unlink(temporary_zip.c_str());
    unlink(temporary_sha.c_str());
    fs::remove_all(staging, error);
    return Status::kArchiveFailed;
  }
  chmod(final_zip.c_str(), kFileMode);
  chmod(final_sha.c_str(), kFileMode);
  fs::remove_all(staging, error);
  summary->archive_path = PublicPath(final_zip);
  summary->detail = "ok";
  return Status::kOk;
}

} // namespace r0dump::file_helper
