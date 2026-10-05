import com.android.build.api.variant.impl.VariantOutputImpl
import li.gkd.gradle.GenerateUiStringsTask
import li.gkd.gradle.buildProperty
import li.gkd.gradle.gitInfo
import li.gkd.gradle.readDebugSuffixResources

val gitInfo = project.gitInfo
val debugSuffixResources = project.readDebugSuffixResources()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.remap)
    alias(libs.plugins.codeorigin)
}

android {
    namespace = "li.gkd.app"
    defaultConfig {
        // CLEAN 自有包名。上线后不可再变更，否则已发放的激活码会全部失效。
        applicationId = "com.clean.click"
        versionCode = 1
        versionName = "1.0.0"

        // CLEAN：无激活码版本。
        //   默认构建（不加参数）-> ACTIVATION_REQUIRED = true，行为与原来完全一致；
        //   加 -PCLEAN_FREE=true 构建 -> 门禁关闭，装完即可用，无需激活码。
        // 用构建期常量而不是改源码，是为了两种包都能从同一份代码出，且不会有人
        // 误把「已放行」的代码提交进去。
        buildConfigField(
            "boolean",
            "ACTIVATION_REQUIRED",
            ((findProperty("CLEAN_FREE") as? String)?.toBoolean() != true).toString(),
        )

        vectorDrawables {
            useSupportLibrary = true
        }
        androidResources {
            localeFilters += listOf("zh-rCN", "en")
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // 单变体化：渠道与无障碍工具标记直接写在 defaultConfig 中（原 productFlavors 已移除）
        manifestPlaceholders["channel"] = "clean"
        resValue("bool", "is_accessibility_tool", "true")

        manifestPlaceholders["buildKey"] = ""
        manifestPlaceholders["commitId"] = gitInfo.commitId
        manifestPlaceholders["commitTime"] = gitInfo.commitTime
        manifestPlaceholders["tagName"] = gitInfo.tagName.orEmpty()
    }

    buildFeatures {
        compose = true
        aidl = true
        resValues = true
        // 无激活码版本需要构建期常量来决定是否启用激活门禁
        buildConfig = true
    }


    val gkdStoreFile = buildProperty("GKD_STORE_FILE").orNull
    val gkdSigningConfig = if (gkdStoreFile != null) {
        signingConfigs.create("gkd") {
            storeFile = file(gkdStoreFile)
            storePassword = buildProperty("GKD_STORE_PASSWORD").orNull
            keyAlias = buildProperty("GKD_KEY_ALIAS").orNull
            keyPassword = buildProperty("GKD_KEY_PASSWORD").orNull
        }
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        all {
            vcsInfo.include = false
            versionNameSuffix = gitInfo.versionNameSuffix
        }
        release {
            // 只在提供了生产签名配置时才签名；否则保持未签名。
            // 刻意不回退到 debug 签名：上游的静默回退会让 release 包"构建成功但签名错误"，
            // 那种包无法覆盖安装、也无法与已发放的激活码对应。
            signingConfig = signingConfigs.findByName("gkd")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = gkdSigningConfig
            applicationIdSuffix = ".debug"
            // CLEAN：原先在 debug 下把 better_black 覆盖成 #FF5D92（粉），用来一眼区分调试包。
            // 但这会让 debug 包的**应用图标、启动图、关于页标记**都变粉，
            // 与正式版（以及「CLEAN 发码器」）的黑白图标不一致，故按需求去掉该覆盖。
            for ((name, value) in debugSuffixResources) {
                resValue("string", name, value)
            }
        }
    }
    // 单变体化：原 productFlavors（gkd / play）整块移除，渠道与无障碍标记已移入 defaultConfig。
    // https://github.com/LSPosed/AndroidHiddenApiBypass
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // https://priv-kit.pages.dev/zh/guide/getting-started#native-library-packaging
    packaging.jniLibs.useLegacyPackaging = true
    packaging.resources.excludes += setOf(
        "META-INF/**",
        "DebugProbesKt.bin",
    )
}

val generateUiStrings = tasks.register<GenerateUiStringsTask>("generateUiStrings") {
    stringsFile.set(layout.projectDirectory.file("src/main/res/values/strings.xml"))
    outputDirectory.set(layout.buildDirectory.dir("generated/source/uiStrings"))
}
androidComponents.onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(generateUiStrings, GenerateUiStringsTask::outputDirectory)
}

// CLEAN 已移除 api.gkd.li 构建产物上传（会把 mapping.txt 与源码清单外发第三方）。
// 原 androidBuildAssetAdapter / configureBuildAssets / releaseBuildKey 注入一并删除；
// 这些代码同时依赖 variant.productFlavors，单变体化后也无法保留。

if (buildProperty("GKD_RENAME_APK_FLAG").isPresent) {
    androidComponents.onVariants { variant ->
        variant.outputs.onEach { output ->
            output as VariantOutputImpl
            output.outputFileName = "clean-v${output.versionName.get()}.apk"
        }
    }
}

composeCompiler {
    if (providers.gradleProperty("composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
    }
    stabilityConfigurationFiles.addAll(
        rootProject.layout.projectDirectory.file("stability_config.conf"),
    )
}

dependencies {
    implementation(libs.kotlin.stdlib)

    implementation(project(":clean-db"))
    implementation(project(":clean-selector"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.animation)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)

    implementation(libs.compose.activity)
    implementation(libs.compose.material3)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    testImplementation(libs.junit)

    implementation(libs.androidx.concurrent.futures)

    remapApi(project(":clean-hidden-api"))
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)
    implementation(libs.priv.kit.ui)
    implementation(libs.lsposed.hiddenapibypass)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.google.accompanist.drawablepainter)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    // https://github.com/Kotlin/kotlinx-atomicfu/issues/145
    implementation(libs.kotlinx.atomicfu)

    implementation(libs.reorderable)
    implementation(libs.morph.compose)

    implementation(libs.androidx.splashscreen)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.coil.gif)
    implementation(libs.telephoto.zoomable)

    implementation(libs.exp4j)

    implementation(libs.toaster)
    implementation(libs.permissions)
    implementation(libs.device)

    implementation(libs.json5)
    compileOnly(libs.codeorigin)

    // compose-webview declares Material but does not use it.
    implementation(libs.kevinnzouWebview) {
        exclude(group = "com.google.android.material", module = "material")
    }
}
