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
    val versionNameSuffix get() = if (tagName == null) ("-" + commitId.take(7)) else null
}

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
    val commitId = runGitCommand(repositoryDirectory, listOf("rev-parse", "HEAD"))
    return GitInfo(
        commitId = commitId,
        commitTime = runGitCommand(repositoryDirectory, listOf("log", "-1", "--format=%ct")) + "000",
        tagName = runCatching {
            runGitCommand(repositoryDirectory, listOf("describe", "--tags", "--exact-match"))
        }.getOrNull(),
    )
}
