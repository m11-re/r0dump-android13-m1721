package com.android.r0dumpmanager.domain

data class CapturePoint(
    val bit: Int,
    val name: String,
    val sourceLocation: String,
    val kind: String,
)

object CapturePointRegistry {
    const val pointCount = 43
    val definedMask: ULong = (1uL shl pointCount) - 1uL
    val undefinedMask: ULong = definedMask.inv()
    val forceNoExecuteMask: ULong = 1uL shl 41
    // Baseline tuned to unpack first- and second-generation shells with whole-DEX
    // capture (the design keeps records few: max_records caps at 4096, so per-method
    // points are deliberately excluded). Covers the common load paths a gen-1 packer
    // hands a decrypted DEX through -- OpenCommon (file), InMemory.dex.parsed
    // (InMemoryDexClassLoader), Cookie.result.dex, RegisterDexFile.return -- plus the
    // active DEX snapshot taken while walking (Walk.cookie.dex). ForceInvoke.no_execute
    // materialises gen-2 method CodeItems into the in-memory image before it is
    // snapshotted; pairing it with walk_mode=dex_names (walk every class) reaches
    // lazily-restored methods without emitting a record per method.
    val defaultMask: ULong = (1uL shl 0) or (1uL shl 2) or (1uL shl 3) or (1uL shl 5) or
        (1uL shl 39) or forceNoExecuteMask
    val points = listOf(
        CapturePoint(0, "Cookie.result.dex", "CreateCookieFromOatFileManagerResult", "DEX snapshot"),
        CapturePoint(1, "InMemory.buffer.copied", "DexFile_openInMemoryDexFilesNative", "raw buffer"),
        CapturePoint(2, "InMemory.dex.parsed", "DexFile_openInMemoryDexFilesNative", "DEX snapshot"),
        CapturePoint(3, "OpenCommon.return", "DexFileLoader::OpenCommon", "DEX snapshot"),
        CapturePoint(4, "DefineClassNative.match", "DexFile_defineClassNative", "DEX snapshot"),
        CapturePoint(5, "RegisterDexFile.return", "ClassLinker::RegisterDexFile", "DEX snapshot"),
        CapturePoint(6, "DefineClass.entry", "ClassLinker::DefineClass", "class/Dex evidence"),
        CapturePoint(7, "DefineClass.success", "ClassLinker::DefineClass", "metadata"),
        CapturePoint(8, "LoadMethod.ready", "ClassLinker::LoadClassHelper::FillMethods", "CodeItem"),
        CapturePoint(9, "LoadClass.entry", "ClassLinker::LoadClass", "metadata"),
        CapturePoint(10, "LoadClass.success", "ClassLinker::LoadClass", "metadata"),
        CapturePoint(11, "ResolveMethod.cache_hit", "ClassLinker::ResolveMethodId", "method evidence"),
        CapturePoint(12, "ResolveMethod.lookup_success", "ClassLinker::ResolveMethodId", "method evidence"),
        CapturePoint(13, "VerifyClass.complete", "ClassLinker::VerifyClass", "metadata"),
        CapturePoint(14, "ClassInit.before_clinit", "ClassLinker::InitializeClass", "CodeItem/metadata"),
        CapturePoint(15, "ClassInit.success", "ClassLinker::InitializeClass", "metadata"),
        CapturePoint(16, "OatDexFile.open.success", "OatDexFile::OpenDexFile", "DEX snapshot"),
        CapturePoint(17, "Vdex.load.complete", "OatFileAssistant::LoadDexFiles", "DEX snapshot"),
        CapturePoint(18, "OatManager.file.complete", "OatFileManager::OpenDexFilesFromOat(path)", "DEX snapshot"),
        CapturePoint(19, "OatManager.memory.complete", "OatFileManager::OpenDexFilesFromOat(memory)", "DEX/reference"),
        CapturePoint(20, "OatManager.register.before_lock", "OatFileManager::RegisterOatFile", "metadata"),
        CapturePoint(21, "ImageSpace.dex.tracked", "OatFileManager::OpenDexFilesFromOat(path)", "DEX/reference"),
        CapturePoint(22, "ArtMethod.invoke.dispatch", "ArtMethod::Invoke", "method evidence"),
        CapturePoint(23, "Interpreter.execute.entry", "interpreter::Execute", "method evidence"),
        CapturePoint(24, "Jit.method_entered", "Jit::MethodEntered", "method evidence"),
        CapturePoint(25, "Jit.compile.begin", "Jit::CompileMethodInternal", "CodeItem/metadata"),
        CapturePoint(26, "Jit.compile.end", "Jit::CompileMethodInternal", "metadata"),
        CapturePoint(27, "Reflect.method.before_invoke", "Method_invoke", "method evidence"),
        CapturePoint(28, "Reflect.ctor.before_invoke", "Constructor_newInstance0", "method evidence"),
        CapturePoint(29, "Instrumentation.enter", "Instrumentation::MethodEnterEventImpl", "method evidence"),
        CapturePoint(30, "Instrumentation.exit.value", "Instrumentation::MethodExitEventImpl", "method evidence"),
        CapturePoint(31, "Instrumentation.exit.object", "Instrumentation::MethodExitEventImpl", "method evidence"),
        CapturePoint(32, "BDCL.path.constructor", "BaseDexClassLoader::<init>(path)", "DEX route"),
        CapturePoint(33, "BDCL.memory.constructor", "BaseDexClassLoader::<init>(memory)", "buffer route"),
        CapturePoint(34, "BDCL.findClass.success", "BaseDexClassLoader::findClass", "DEX route"),
        CapturePoint(35, "BDCL.addDexPath.return", "BaseDexClassLoader::addDexPath", "DEX route"),
        CapturePoint(36, "DexFile.open_file.return", "DexFile::openDexFile", "DEX route"),
        CapturePoint(37, "DexFile.open_memory.return", "DexFile::openInMemoryDexFiles", "buffer route"),
        CapturePoint(38, "DexFile.before_define", "DexFile::loadClassBinaryName", "DEX route"),
        CapturePoint(39, "Walk.cookie.dex", "r0dump_Runtime_r0dump_CaptureCookie", "active DEX snapshot"),
        CapturePoint(40, "Walk.executable.method", "r0dump_Runtime_r0dump_CaptureExecutable", "active method evidence"),
        CapturePoint(41, "ForceInvoke.no_execute", "ArtMethod::Invoke", "active CodeItem"),
        CapturePoint(42, "Nterp.method.entry", "ExecuteNterpImpl", "Nterp method evidence"),
    )
    init { check(points.map { it.bit } == (0 until pointCount).toList()) }
    fun contains(mask: ULong, bit: Int) = bit in 0 until pointCount && (mask and (1uL shl bit)) != 0uL
    fun toggle(mask: ULong, bit: Int): ULong = if (bit !in 0 until pointCount) mask else mask xor (1uL shl bit)
}
