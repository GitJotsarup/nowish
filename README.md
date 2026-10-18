# Nowish ✨

A simple, good-looking Android to-do app for tasks you'll get to now-ish.

- **Work / Personal** spaces, each with its own sections: **Overdue · Today · Tomorrow · Upcoming · Later**
- **Swipe right** to clear a task (with confetti, haptics and a success chime 🎉), **swipe left** to delete. Both can be undone.
- **Tap** a task to edit its text inline. **Long-press** for more: date, reminder time, list, move to Work/Personal, delete.
- **Custom lists** (🎬 To watch, 🛒 Groceries…) for each space, from the side menu
- **Reminders** fire at an exact time, with **Mark done** and **Snooze 1h** buttons on the notification. They survive a phone restart.
- **Material You**: colors follow your wallpaper (Android 12+), light and dark themes, and a themed icon.

## Install

Grab the latest APK from **[Releases](../../releases/latest)** and open it on your phone. You may need to allow "Install unknown apps" for your browser or file manager.

Every push to `main` builds a new signed APK through GitHub Actions and publishes it as a release. Installing a newer APK updates the app in place and keeps your tasks.

## Building locally

Open the folder in Android Studio, or run `gradle assembleRelease` with Gradle 8.11 and JDK 17.
Without the signing secrets, release builds are signed with the debug key.

### Signing (CI)

The workflow reads these repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Keep the keystore backed up: Android only installs updates that are signed with the same key.
