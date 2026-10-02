package li.gkd.gradle

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.Serializable

// CLEAN: 本文件原先还包含 releaseBuildKey / GitBuildKeyValueSource / readRepositoryStateId
// 与 GitOutputValueSource，它们只服务于已移除的 api.gkd.li 构建产物上传，
// 已随 BuildAsset.kt / UploadBuildAssetTask.kt / GenerateSourcePathsTask.kt 一并删除。
// 保留的部分只提供 git 版本信息，用于 versionNameSuffix 与 Manifest 元数据。

private const val GIT_INFO_SERVICE_NAME = "gkdGitInfo"

private fun runGitCommand(
    repositoryDirectory: String,
    arguments: List<String>,
): String {
    val process = ProcessBuilder(
        listOf("git", "-C", repositoryDirectory) + arguments,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Command failed with exit code $exitCode: ${output.toString(Charsets.UTF_8)}")
    }
    return output.toString(Charsets.UTF_8).trim()
}

data class GitInfo(
    val commitId: String,
    val commitTime: String,
    val tagName: String?,
) : Serializable {
    val isUnknown get() = commitId == UNKNOWN_COMMIT_ID

    // 未知来源（源码压缩包 / 未安装 git）时不追加后缀，
    // 避免出现 "-0000000" 这种没有信息量的 versionNameSuffix。
    val versionNameSuffix get() = if (tagName == null && !isUnknown) ("-" + commitId.take(7)) else null
}

/**
 * 源码压缩包（例如 GitHub 的 "Download ZIP"）不含 `.git` 目录，环境也可能没装 git。
 * 这两种情况下不能让构建直接失败：git 信息只用于 versionNameSuffix 与 Manifest 元数据，
 * 回退到占位值即可，不影响任何功能。
 */
private const val UNKNOWN_COMMIT_ID = "0000000000000000000000000000000000000000"

abstract class GitInfoValueSource : ValueSource<GitInfo, GitInfoValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repositoryDirectory: DirectoryProperty
    }

    override fun obtain(): GitInfo {
        return readGitInfo(parameters.repositoryDirectory.get().asFile.absolutePath)
    }
}

abstract class GitInfoService : BuildService<GitInfoService.Parameters> {
    interface Parameters : BuildServiceParameters {
        val gitInfo: Property<GitInfo>
    }

    val gitInfo: GitInfo by lazy {
        parameters.gitInfo.get()
    }
}

val Project.gitInfo: GitInfo
    get() = gradle.sharedServices.registerIfAbsent(
        GIT_INFO_SERVICE_NAME,
        GitInfoService::class.java,
    ) {
        parameters.gitInfo.set(
            providers.of(GitInfoValueSource::class.java) {
                parameters.repositoryDirectory.set(rootProject.layout.projectDirectory)
            },
        )
    }.get().gitInfo

private fun readGitInfo(repositoryDirectory: String): GitInfo {
    return runCatching {
        val commitId = runGitCommand(repositoryDirectory, listOf("rev-parse", "HEAD"))
        GitInfo(
            commitId = commitId,
            commitTime = runGitCommand(repositoryDirectory, listOf("log", "-1", "--format=%ct")) + "000",
            tagName = runCatching {
                runGitCommand(repositoryDirectory, listOf("describe", "--tags", "--exact-match"))
            }.getOrNull(),
        )
    }.getOrElse { e ->
        // 没有 .git（源码压缩包）或环境无 git 时回退，而不是让整个构建失败。
        // 打印一行提示，便于区分「真的没有仓库」与「git 用不了」。
        println(
            "[gkd-gradle] 无法读取 git 信息（$repositoryDirectory）：" +
                "${e.message?.lineSequence()?.firstOrNull() ?: e::class.java.simpleName}；" +
                "回退到占位值，versionNameSuffix 与 commit 元数据将为空。"
        )
        GitInfo(
            commitId = UNKNOWN_COMMIT_ID,
            commitTime = "0",
            tagName = null,
        )
    }
}
