# WOS Fast Rally — initial prototype

This is a **dry-run only** Android prototype. It shows floating F5/F6/F7 selectors and logs accessibility events. It **does not tap the game, select formations, or deploy troops**. The event filter may need adjustment after inspecting real package names and logs.

## Build from a phone
1. Create a private GitHub repository with default branch `main`.
2. Upload the *contents* of this project to the repository, preserving `.github/workflows/build.yml`.
3. Open **Actions → Build Android APK → Run workflow** (or wait for the push build).
4. When successful, open the run → **Artifacts** → download `WosFastRally-debug-apk`.
5. Extract the ZIP and install `app-debug.apk` on Android. Review installation and accessibility prompts.
6. Open the app, grant overlay permission, then enable its accessibility service. A floating selector should appear.

## Known limitations
- No automated gameplay in this version.
- Overlay placement is fixed; it may obscure UI on some devices.
- The project has not been compiled in this environment; GitHub Actions is the first compilation test.
- GitHub Actions build minutes and artifact availability depend on your account settings.
- Accessibility events from games may be incomplete or absent.
