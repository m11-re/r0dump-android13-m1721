#include "r0dump_export_core.h"
#include "r0dump_file_helper_core.h"

#include <fcntl.h>
#include <gtest/gtest.h>
#include <json/json.h>
#include <sys/stat.h>
#include <unistd.h>
#include <ziparchive/zip_archive.h>
#include <ziparchive/zip_writer.h>

#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

namespace r0dump::file_helper {
namespace {

class HelperTest : public testing::Test {
protected:
  void SetUp() override {
    char templ[] = "/tmp/r0dump-helper-XXXXXX";
    root_ = mkdtemp(templ);
    ASSERT_FALSE(root_.empty());
    std::filesystem::create_directories(
        root_ + "/Android/data/com.example.target/files");
    root_fd_ = open(root_.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    ASSERT_GE(root_fd_, 0);
  }

  void TearDown() override {
    if (root_fd_ >= 0)
      close(root_fd_);
    std::filesystem::remove_all(root_);
  }

  Request RequestFor(uint32_t pid = 1234u) {
    Request request = {};
    request.magic = kProtocolMagic;
    request.version = kProtocolVersion;
    request.size = static_cast<uint16_t>(sizeof(Request));
    request.generation = 2u;
    request.user_id = 0u;
    request.target_uid = 10001u;
    request.target_pid = pid;
    strcpy(request.package_name, "com.example.target");
    strcpy(request.process_name, "com.example.target:worker");
    strcpy(request.run_id, "run-1");
    return request;
  }

  std::string root_;
  int root_fd_ = -1;
};

mode_t Mode(const std::string &path) {
  struct stat st{};
  EXPECT_EQ(stat(path.c_str(), &st), 0);
  return st.st_mode & 02777;
}

TEST_F(HelperTest, CreatesSetgidTreeAndReturnsProcessDirectory) {
  int process_fd = -1;
  EXPECT_EQ(PrepareDirectoryTreeAt(root_fd_, RequestFor(),
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kOk);
  ASSERT_GE(process_fd, 0);
  struct stat st{};
  ASSERT_EQ(fstat(process_fd, &st), 0);
  EXPECT_TRUE(S_ISDIR(st.st_mode));
  EXPECT_EQ(fcntl(process_fd, F_GETFL) & O_PATH, O_PATH);
  EXPECT_EQ(st.st_gid, getegid());
  EXPECT_EQ(st.st_mode & 02777, static_cast<mode_t>(02770));
  close(process_fd);

  const std::string run =
      root_ + "/Android/data/com.example.target/files/r0dump-fusion/runs/run-1";
  EXPECT_EQ(Mode(run), static_cast<mode_t>(02770));
  const std::string process = run + "/process-1234";
  EXPECT_EQ(Mode(process), static_cast<mode_t>(02770));
  for (const char *name : {"metadata", "artifacts", "records", "methods"}) {
    EXPECT_EQ(Mode(process + "/" + name), static_cast<mode_t>(02770));
  }
}

TEST_F(HelperTest, AcceptsSystemUidForTargetProcess) {
  Request request = RequestFor();
  request.target_uid = 1000u;
  int process_fd = -1;
  EXPECT_EQ(PrepareDirectoryTreeAt(root_fd_, request,
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kOk);
  EXPECT_GE(process_fd, 0);
  close(process_fd);
}

TEST_F(HelperTest, RejectsZeroTargetUid) {
  Request request = RequestFor();
  request.target_uid = 0u;
  int process_fd = -1;
  EXPECT_EQ(PrepareDirectoryTreeAt(root_fd_, request,
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kProtocolError);
  EXPECT_EQ(process_fd, -1);
}

TEST_F(HelperTest, RejectsDuplicateProcessDirectoryWithoutReusingIt) {
  int process_fd = -1;
  Request request = RequestFor();
  ASSERT_EQ(PrepareDirectoryTreeAt(root_fd_, request,
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kOk);
  close(process_fd);
  process_fd = -1;
  EXPECT_EQ(PrepareDirectoryTreeAt(root_fd_, request,
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kExists);
  EXPECT_EQ(process_fd, -1);
}

TEST_F(HelperTest, RejectsExistingRunWithUntrustedOwnershipWithoutRepairingIt) {
  const std::string run =
      root_ + "/Android/data/com.example.target/files/r0dump-fusion/runs/run-1";
  std::filesystem::create_directories(run);
  ASSERT_EQ(chmod(run.c_str(), 0755), 0);

  struct stat before{};
  ASSERT_EQ(stat(run.c_str(), &before), 0);
  int process_fd = -1;
  EXPECT_EQ(PrepareDirectoryTreeAt(root_fd_, RequestFor(),
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kPermissionDenied);
  EXPECT_EQ(process_fd, -1);

  struct stat after{};
  ASSERT_EQ(stat(run.c_str(), &after), 0);
  EXPECT_EQ(after.st_uid, before.st_uid);
  EXPECT_EQ(after.st_gid, before.st_gid);
  EXPECT_EQ(after.st_mode & 02777, before.st_mode & 02777);
  EXPECT_FALSE(std::filesystem::exists(run + "/process-1234"));
}

TEST_F(HelperTest, RejectsSymlinkInTrustedPath) {
  std::filesystem::remove_all(root_ + "/Android/data/com.example.target");
  ASSERT_EQ(
      symlink("/tmp", (root_ + "/Android/data/com.example.target").c_str()), 0);
  int process_fd = -1;
  EXPECT_NE(PrepareDirectoryTreeAt(root_fd_, RequestFor(),
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kOk);
  EXPECT_EQ(process_fd, -1);
}

TEST_F(HelperTest, RejectsPathLikeRunId) {
  Request request = RequestFor();
  strcpy(request.run_id, "../escape");
  int process_fd = -1;
  EXPECT_EQ(PrepareDirectoryTreeAt(root_fd_, request,
                                   {geteuid(), geteuid(), getegid()},
                                   &process_fd),
            Status::kProtocolError);
}

TEST_F(HelperTest, ExportLatestPackagesRepairReportApkAndManifest) {
  const std::string process =
      root_ + "/Android/data/com.example.target/files/r0dump-fusion/runs/"
              "run-1/process-1234";
  std::filesystem::create_directories(process + "/artifacts");
  std::filesystem::create_directories(process + "/records");
  std::ofstream(process + "/session.json") << "{}\n";
  std::ofstream(process + "/artifacts/bad.dex", std::ios::binary)
      << "not-a-dex";
  std::ofstream(process + "/artifacts/before.codeitem.raw", std::ios::binary)
      << "before";
  std::ofstream(process + "/artifacts/nterp.codeitem.raw", std::ios::binary)
      << "after";
  std::ofstream(process + "/records/000001.json")
      << R"({"capture_kind":"dexfile","artifact_relative_path":"artifacts/bad.dex","site":"test","capture_point_name":"test.point"})"
      << "\n";
  std::ofstream(process + "/records/000002.json")
      << R"({"capture_kind":"codeitem","artifact_relative_path":"artifacts/before.codeitem.raw","artifact_fast_hash":"1111","dex_object_id":99,"method_index":7,"active_attempt_id":3,"entry_kind":"nterp","observation_phase":"before_dispatch"})"
      << "\n";
  std::ofstream(process + "/records/000003.json")
      << R"({"capture_kind":"codeitem","artifact_relative_path":"artifacts/nterp.codeitem.raw","artifact_fast_hash":"2222","dex_object_id":99,"method_index":7,"active_attempt_id":3,"entry_kind":"nterp","observation_phase":"nterp_entry"})"
      << "\n";

  const std::string apk = root_ + "/base.apk";
  FILE *apk_file = fopen(apk.c_str(), "wb");
  ASSERT_NE(apk_file, nullptr);
  ZipWriter writer(apk_file);
  ASSERT_EQ(writer.StartEntry("AndroidManifest.xml", 0), 0);
  constexpr char manifest[] = "binary-manifest-test";
  ASSERT_EQ(writer.WriteBytes(manifest, sizeof(manifest) - 1u), 0);
  ASSERT_EQ(writer.FinishEntry(), 0);
  ASSERT_EQ(writer.Finish(), 0);
  ASSERT_EQ(fclose(apk_file), 0);
  const int apk_fd = open(apk.c_str(), O_RDONLY | O_CLOEXEC);
  ASSERT_GE(apk_fd, 0);

  ExportSummary summary;
  EXPECT_EQ(ExportLatest(root_, root_ + "/Downloads", "com.example.target",
                         {apk_fd}, &summary),
            Status::kOk);
  close(apk_fd);
  EXPECT_EQ(summary.run_id, "run-1");
  EXPECT_EQ(summary.remote_artifact_count, 3u);
  EXPECT_EQ(summary.record_count, 3u);
  EXPECT_EQ(summary.partial, 3u);
  EXPECT_TRUE(std::filesystem::is_regular_file(summary.archive_path));
  EXPECT_TRUE(
      std::filesystem::is_regular_file(summary.archive_path + ".sha256"));

  ZipArchiveHandle archive = nullptr;
  ASSERT_EQ(OpenArchive(summary.archive_path.c_str(), &archive), 0);
  ZipEntry manifest_entry;
  EXPECT_EQ(FindEntry(archive,
                      "com.example.target/run-1/manifest/AndroidManifest.xml",
                      &manifest_entry),
            0);
  ZipEntry report_entry;
  EXPECT_EQ(FindEntry(archive,
                      "com.example.target/run-1/process-1234/host-repair/"
                      "report.json",
                      &report_entry),
            0);
  std::vector<uint8_t> report_bytes(report_entry.uncompressed_length);
  ASSERT_EQ(ExtractToMemory(archive,
                            &report_entry,
                            report_bytes.data(),
                            report_entry.uncompressed_length),
            0);
  const std::string report_text(report_bytes.begin(), report_bytes.end());
  Json::CharReaderBuilder json_builder;
  std::unique_ptr<Json::CharReader> json_reader(json_builder.newCharReader());
  Json::Value report;
  std::string json_errors;
  ASSERT_TRUE(json_reader->parse(report_text.data(),
                                 report_text.data() + report_text.size(),
                                 &report,
                                 &json_errors))
      << json_errors;
  const Json::Value &paths = report["method_execution_paths"];
  ASSERT_TRUE(paths.isArray());
  ASSERT_EQ(paths.size(), 1u);
  EXPECT_EQ(paths[0]["dex_object_id"].asUInt64(), 99u);
  EXPECT_EQ(paths[0]["method_index"].asUInt(), 7u);
  EXPECT_EQ(paths[0]["active_attempt_id"].asUInt64(), 3u);
  EXPECT_EQ(paths[0]["first_entry_kind"].asString(), "nterp");
  ASSERT_EQ(paths[0]["observed_phases"].size(), 2u);
  EXPECT_EQ(paths[0]["observed_phases"][0].asString(), "before_dispatch");
  EXPECT_EQ(paths[0]["observed_phases"][1].asString(), "nterp_entry");
  EXPECT_EQ(paths[0]["first_artifact_fast_hash"].asString(), "1111");
  EXPECT_EQ(paths[0]["last_artifact_fast_hash"].asString(), "2222");
  EXPECT_TRUE(paths[0]["code_item_changed"].asBool());
  CloseArchive(archive);
}

TEST_F(HelperTest, ExportLatestRejectsPackageWithoutCapturedRun) {
  ExportSummary summary;
  EXPECT_EQ(ExportLatest(root_, root_ + "/Downloads", "com.example.target",
                         {STDIN_FILENO}, &summary),
            Status::kNoRuns);
  EXPECT_EQ(summary.detail, "no_run_with_artifacts");
}

} // namespace
} // namespace r0dump::file_helper
