# MY DS Connect — integration plan (base 4.29.0)
Keep main untouched while Claude and ChatGPT work in parallel. This is a research/design handoff, not a working receiver.
## Sources
- https://github.com/andreknieriem/open-headunit — Android Auto receiver; Native wireless handshake, Wi-Fi Direct/hotspot, experimental ZLink Bluetooth route. Review GPL/AGPL obligations before copying; initially test as separate installed app.
- https://github.com/andreknieriem/open-headunit/wiki/Wireless — Android Auto 17.4+ legacy trigger restrictions; prefer native mode, no root requirement for initial test.
- https://developer.android.com/reference/android/media/session/MediaSessionManager — local media sessions; notification access may be required.
- https://github.com/spotify/android-sdk — optional phone-side Spotify control; authorization and app requirements apply.
## Existing code
AppConnectScreen.java launches ZLink exported activities; StartupConnect.java attempts startup connections; BluetoothSource.java uses unit A2DP/AVRCP and media sessions. Do not rewrite or remove these fallbacks.
## Milestones
1. Parked diagnostic: report unit type, Android API, ZLink package/version, exported projection activities, Bluetooth backend, A2DP connection, media browser availability, Wi-Fi state, without collecting identifiers or credentials. Surface failures instead of silently swallowing exceptions.
2. Test Open Headunit as separate receiver, native mode and USB baseline; avoid concurrent ownership of Bluetooth/Wi-Fi with ZLink. No root, firmware changes or CAN writes.
3. Native MY DS media UI: playback state/title/artist/artwork when supplied by AVRCP or MediaSession; play/pause/skip; retain opt-in autoplay and audio focus behavior. Do not promise phone playlist browsing through generic AVRCP.
4. Projection: launch chosen receiver from MY DS, preserve existing ZLink fallback. Embedding projection requires receiver/rendering integration and license review, not simply launching an Activity.
5. Optional paired Android phone companion for authorized media session controls over authenticated local Wi-Fi; no raw streaming or DRM bypass. Maps projection via Android Auto; independent navigation UI would require licensed SDK and separate design.
6. Acceptance on Samsung phone + DS4 unit: cold boot reconnect, Spotify/YT Music audio and control, Maps navigation audio, CataraPatara ducking, calls, loss/reconnect, no unexpected playback. Verify actual devices before claiming working.
