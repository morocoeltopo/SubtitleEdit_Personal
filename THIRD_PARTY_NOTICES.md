# Third-Party Notices

SubtitleEdit for Android includes the following archive-related components.
Their licenses apply to those components independently of the project's
GPL-3.0 license.

## 7-Zip 26.02

Copyright (C) 1999-2026 Igor Pavlov.

Most 7-Zip source files are licensed under LGPL-2.1-or-later. The RAR decoder
files additionally carry the unRAR restriction. LZFSE and Zstandard decoder
files use the BSD 3-Clause License, and XXH64 uses the BSD 2-Clause License.
The complete upstream notice and all applicable license terms are distributed
in `app/src/main/cpp/third_party/7zip/7zip-LICENSE.txt` and in the APK at
`assets/licenses/7zip-LICENSE.txt`.

The unRAR-derived sources must not be used to develop a RAR-compatible
archiver. This application only exposes RAR extraction.
