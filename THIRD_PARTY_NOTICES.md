# Fold8 glass renderer

Build 77 replaces the former external `duo_fold.agsl` dependency with this fork's
own glass projection in `DuoSnapshotRenderer.java` and Android's native Gaussian
blur. This implementation is included under the repository's MIT license.
No foldtoduo shader, Apple images, or reference-video assets are bundled.

Earlier local builds used a shader from [kuris/foldtoduo](https://github.com/kuris/foldtoduo).
It had no license statement in the inspected upstream revision and is not included
in this source or APK. If upgrading an old checkout, remove its downloaded
`app/src/main/res/raw/duo_fold.agsl` before building.

---

# 第三者のソフトウェアとライセンス

この一覧はFolduoの依存関係を2026年9月13日に確認したものです。自作のアプリ・設定補助・文書はルートの[MITライセンス](LICENSE)に従います。第三者のコードの権利はそれぞれの著作権者に帰属します。依存ライブラリそのものは改変していません。

## 配布APKの依存関係

| ソフトウェア | 版 | ライセンス・表示 |
| --- | --- | --- |
| Shizuku API / provider / aidl / shared | 13.1.5 | MIT、Copyright (c) 2021 RikkaW。[全文](licenses/Shizuku-MIT.txt) |
| Kotlin標準ライブラリ | 2.2.10 | Apache 2.0、Copyright 2010–2024 JetBrains s.r.o. and Kotlin Programming Language contributors。[表示](licenses/Kotlin-COPYRIGHT.txt) |
| JetBrains annotations | 13.0 | Apache 2.0、JetBrains s.r.o. |
| AndroidX annotation | 1.3.0 | Apache 2.0、The Android Open Source Project |

Kotlin標準ライブラリの共通/JVMコードにはGWT・Guava由来のApache 2.0コード、ThreeTen由来のBSD 3-Clauseコード、Boost由来のコードが含まれます。[出典と著作権表示](licenses/Kotlin-JVM-attribution.txt)、[BSD全文](licenses/Kotlin-ThreeTen-BSD-3-Clause.txt)、[Boost全文](licenses/Kotlin-Boost-1.0.txt)を保存しています。

[Apache License 2.0の全文](licenses/Apache-2.0.txt)も同梱しています。ビルド時に、この文書、ルートのLICENSE、licenses配下の原文をAPKの `assets/licenses/` へコピーします。APKだけを再配布する場合も表示が残ります。

Shizuku API 13.1.5のMaven POMが宣言するライセンスはMITです。別製品であるShizuku管理アプリ本体のライセンスと混同しないでください。管理アプリはこのリポジトリ・APKに同梱していません。

## ビルド・試験に用いるもの

| ソフトウェア | 版 | 用途・ライセンス |
| --- | --- | --- |
| Gradle Wrapper | 9.5.1 | リポジトリに含む起動用コード。Apache 2.0、Gradle, Inc.およびcontributors。各スクリプトの表示とJAR内のMETA-INF/LICENSEを保持 |
| Android Gradle Plugin | 9.2.1 | ビルド時に取得。Apache 2.0 |
| JUnit | 4.13.2 | 単体試験のみ。[EPL 1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |
| Hamcrest Core | 1.3 | JUnitの試験用依存。[BSD 3-Clause](https://github.com/hamcrest/JavaHamcrest/blob/hamcrest-java-1.3/LICENSE.txt) |
| AndroidX Test runner / rules / ext.junit | 1.7.0 / 1.7.0 / 1.3.0 | 端末試験のみ。Apache 2.0 |

JUnit、Hamcrest、AndroidX Testのバイナリはrelease APKに含みません。Android SDK、JDK、Gradle配布本体は利用者が各提供元の条件に従って別途取得します。

## 含まないもの

Samsungの壁紙・動画・ファームウェア・逆コンパイルしたコード、Appleの画像・アイコン、SNSの投稿画像・動画、Shizuku管理アプリのAPKは配布しません。設定補助は、利用者の対応端末に元から存在する純正壁紙を設定する自作コードです。純正の素材をPCへ取り出したり、この配布物に含めたりしません。

製品名は互換性と着想を説明するためのものです。本プロジェクトはApple、Samsung、Shizukuの公式製品ではなく、提携・推奨を受けたものでもありません。MITライセンスは第三者の商標、特許、端末ソフトウェアの利用条件に関する権利を付与するものではありません。

## 確認元

- [Shizuku APIのMIT原文](https://github.com/RikkaApps/Shizuku-API/blob/ffb074f10cf615ca96f2153b1aee604a50503be0/LICENSE)
- [Shizuku API 13.1.5のPOM](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.pom)
- [Kotlin 2.2.10のライセンス一覧](https://github.com/JetBrains/kotlin/blob/v2.2.10/license/README.md)
- [AndroidXのライセンス](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)
- [Gradle 9.5.1のライセンス](https://github.com/gradle/gradle/blob/v9.5.1/LICENSE)

監査時には `:app:dependencies --configuration releaseRuntimeClasspath` の結果と、実際のMaven POM、配布APKの内容を照合しました。
