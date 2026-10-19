# Nowish ✨

A simple, good-looking to-do app for tasks you'll get to now-ish: on Android, on a home-screen widget, and on the web.

- **Work / Personal** spaces, each with its own sections: **Overdue · Today · Tomorrow · Upcoming · Later**
- **Swipe right** to clear a task (with confetti, haptics and a success chime 🎉), **swipe left** to delete. Both can be undone.
- **Tap** a task to edit its text inline. **Long-press** for more: date, reminder time, list, move to Work/Personal, delete.
- **Custom lists** (🎬 To watch, 🛒 Groceries…) for each space
- **Reminders** fire at an exact time, with **Mark done** and **Snooze 1h** buttons on the notification
- **Widget**: pick Today / Upcoming / Later / Everything / a list, Work, Personal or both, plus text size and a see-through background. Resize it to any size and tap a circle to tick a task off.
- **Web app** for your laptop at **https://gitjotsarup.github.io/nowish/**, kept in sync with the phone
- **Material You**: colors follow your wallpaper, light and dark themes, and a themed icon

## Install

Grab the latest APK from **[Releases](../../releases/latest)** and open it on your phone. Installing a newer APK updates the app in place and keeps your tasks.

## Sync (phone ⇄ laptop)

There are no accounts or servers. Tasks live in a single `nowish.json` file in the **private** repo `GitJotsarup/nowish-data`. The phone and the website each read the file, merge it with their own copy (the newest edit of each task wins, and deletes sync too), then write it back.

1. Create a **fine-grained personal access token** at <https://github.com/settings/personal-access-tokens/new>:
   - Repository access: **Only select repositories → nowish-data**
   - Permissions: **Repository permissions → Contents → Read and write**
   - Pick a long expiration
2. **Phone:** ☰ → *Sync with laptop* → paste the token → *Save & sync*.
3. **Laptop:** open the website and paste the same token.

The phone syncs right after every change, every 20 s while it's open, and every ~15 min in the background. The website syncs after every change and every 20 s. The token is stored only on your devices.

## How it's built

- `app/`: Kotlin, Jetpack Compose, Material 3, Room, Glance (widget), WorkManager
- `web/`: plain HTML/CSS/JS with no build step, deployed to GitHub Pages by `.github/workflows/pages.yml`
- `.github/workflows/build.yml`: builds and signs the APK on every push and publishes a release

### Signing (CI)

The workflow reads these repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Keep the keystore backed up: Android only installs updates that are signed with the same key.
