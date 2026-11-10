# app-status

`test-builds.json` is read by SatMap's test builds (the IPA and APK from GitHub releases; never App Store or Google Play
builds). With `"testBuilds": "off"` every test build that is online locks within minutes and shows `title`, `message` and
store buttons for the links given (only apps.apple.com, play.google.com and github.com/JacobusOettle links are shown).
`"on"` unlocks them again. Flip it with the "Switch test builds" workflow (Actions tab) or by editing this file.
