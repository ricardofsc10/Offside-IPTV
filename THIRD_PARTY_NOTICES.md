# Third-party notices

Offside IPTV is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)).
It includes the following components, each under its own licence.

| Component | Licence |
| --- | --- |
| [Jellyfin Media3 FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media) (`org.jellyfin.media3:media3-ffmpeg-decoder`) | GPL-3.0 |
| [FFmpeg](https://ffmpeg.org), bundled inside the Jellyfin decoder | LGPL-2.1-or-later / GPL, depending on the build. The Jellyfin repository publishes the build configuration and source. |
| [AndroidX](https://developer.android.com/jetpack/androidx): Compose, Compose for TV, Activity, Lifecycle, DataStore, Room, Paging | Apache-2.0 |
| [AndroidX Media3](https://github.com/androidx/media): ExoPlayer, HLS, OkHttp data source | Apache-2.0 |
| [Kotlin](https://kotlinlang.org), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 |
| [Coil](https://coil-kt.github.io/coil/) | Apache-2.0 |
| [ZXing core](https://github.com/zxing/zxing) | Apache-2.0 |
| [Barlow Semi Condensed](https://github.com/jpt/barlow) font by Jeremy Tribby | SIL Open Font License 1.1, full text in [docs/OFL-Barlow.txt](docs/OFL-Barlow.txt) |

Test-only dependencies (JUnit, kXML2, MockWebServer, AndroidX Test) are not part of the app.
