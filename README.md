# exp-txt — Plain-Text Android File Explorer

A 100% Kotlin + Jetpack Compose Android file explorer with an ASCII / plain-text visual design inspired by [Plain Text Sports](https://plaintextsports.com/) and official [Dracula & Alucard](https://draculatheme.com/spec) color themes.

## Key Features

- **100% Plain-Text / ASCII Interface**:
  - Monospace typography across the entire UI (`FontFamily.Monospace`).
  - Zero graphical icons — uses pure text badges (`[DIR]`, `[ARC]`, `[TXT]`, `[AUD]`, `[VID]`, `[IMG]`, `[APK]`, `[LNK]`, `[..]`), ASCII box-drawing frames (`+---+`, `|`, `===`, `---`), and text-bracket links (`[+DIR]`, `[+TXT]`, `[EXTRACT]`, `[COMPRESS]`).
- **Official Dracula & Alucard Themes**:
  - **Dracula Classic (Dark)**: `#282A36` background, `#F8F8F2` foreground, `#BD93F9` purple, `#8BE9FD` cyan, `#50FA7B` green, `#FFB86C` orange, `#FF79C6` pink, `#F1FA8C` yellow, `#FF5555` red.
  - **Alucard Classic (Light)**: `#FFFBEB` background, `#1F1F1F` foreground, `#644AC9` purple, `#036A96` cyan, `#14710A` green, `#A34D14` orange, `#A3144D` pink, `#846E15` yellow, `#CB3A2A` red.
- **Both Dual-Pane Designs**:
  - **Split-Pane Mode (`SPLIT-V` & `SPLIT-H`)**: View Pane 1 and Pane 2 simultaneously (stacked Top/Bottom for portrait or Left/Right for landscape/foldables) with one-tap `[SWAP]` and `[SYNC->]` controls.
  - **Swipeable Pager Mode (`PAGER`)**: Full-screen horizontal pager with PlainTextSports-style ASCII scoreboard cards (`+--- PANE 1 ---+` / `+--- PANE 2 ---+`) at the top.
  - **Single-Pane Mode (`SINGLE`)**: Full-screen focus on the active pane.
- **Long-Press File Details & Action Modal**:
  - **Single tap** opens folders, browses inside archives as virtual directories, or opens text/external files.
  - **Long press** highlights the row and opens the `+-- [HIGHLIGHTED ITEM DETAILS & OPTIONS] --+` modal with POSIX & octal permissions (`drwxr-xr-x` / `0755`), interactive `CHMOD` bit toggles, exact byte size, recursive folder/file counts, live **CRC-32, MD5, SHA-1, and SHA-256** checksums, archive compression ratio summary, UTF-8 head preview, and cross-pane actions (`[COPY -> P2]`, `[MOVE -> P2]`, `[EXTRACT -> P2]`, `[COMPRESS...]`, `[RENAME]`, `[DELETE]`).
- **Comprehensive Archive Engine**:
  - Powered by **Apache Commons Compress**, **XZ for Java**, and **Zip4j**.
  - **Virtual Archive Browsing**: Step directly inside `.zip` (including AES-256 password-protected ZIPs), `.tar`, `.tar.gz` / `.tgz`, `.tar.bz2` / `.tbz2`, `.tar.xz` / `.txz`, `.7z`, `.gz`, `.bz2`, `.xz`, `.jar`, and `.apk` files without extracting first.
  - **Full & Selective Extraction**: Extract whole archives or individual virtual files/folders to the current pane or opposite pane with Zip-Slip path traversal protection.
  - **Archive Creation**: Compress single or multiple selected files/folders into `.zip` (with optional AES-256 password), `.tar.gz`, `.tar.xz`, `.tar.bz2`, `.tar`, or `.7z`.
- **All Files Access (`MANAGE_EXTERNAL_STORAGE`)**:
  - Full device storage access on Android 11+ (`API 30–35`) with legacy permission fallback for Android 8.0–10 (`API 26–29`).

## GitHub Actions Release APK Build

The included `.github/workflows/build-release-apk.yml` workflow builds a signed, immediately installable Release APK (`exp-txt-v1.0.0-release.apk`) on every push, pull request, or manual `workflow_dispatch` trigger and uploads it as a GitHub Actions artifact (`exp-txt-Release-APK`).
