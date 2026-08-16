# The Linux userland

The terminal in Conquest Code can run a real **Debian**, with `apt` and the
tens of thousands of packages that come with it. This page explains what
that is, what it can and cannot do, and where things live.

Available in the **`full` edition** only — see [BUILDING.md](BUILDING.md)
for why.

## Installing it

Open the terminal (`Ctrl+`` `, or `❯_` in the status bar). If no userland is
installed, a bar offers one: **Install Debian** — about 30 MB to download,
roughly 100 MB on disk once unpacked. The shell you already have keeps
working while it downloads.

When it finishes, the session restarts inside Debian and your prompt lands
in `/projects/<your project>`.

## Using it

It is an ordinary Debian:

```sh
apt update
apt install git python3 build-essential
```

You are `root` inside the userland, which is why `apt` works without
`sudo`. That root is confined to the userland: nothing outside your app's
own storage can be touched, and the rest of the phone is unaffected.

## Where your files are

| Inside the userland | What it is |
|---|---|
| `/projects` | all your Conquest Code projects |
| `/projects/<name>` | one project — the same files the editor has open |
| `/root` | the userland's home directory, with your `.bashrc` |
| `/tmp`, `/etc`, `/usr` … | ordinary Debian |

The project directory is the important one: the terminal and the editor are
looking at the same files, so a `git clone` or a code generator run in the
shell shows up in the project panel immediately.

## How it works, briefly

Android does not allow an app to run a program that was downloaded rather
than installed — which would make `apt` impossible — unless the app targets
an older SDK. The `full` edition does, which is also why it cannot be
distributed on Google Play.

Debian's own binaries expect to live at `/usr/bin`, `/etc` and so on. They
are actually inside the app's private storage, so **proot** sits in between,
translating those paths for every process it starts. No root, no
virtualisation: proot uses `ptrace` to rewrite what the guest sees.

## What it cannot do

- **Systemd, services, containers.** There is no init system and no
  privileged operations; `systemctl` and `docker` will not work.
- **Anything needing real root**, such as mounting filesystems or raw
  network sockets. `ping` may not work; `curl` and `git` are fine.
- **Speed.** proot intercepts system calls, so heavy compilation is slower
  than the same work on a Linux machine. Ordinary shell work feels normal.
- **Background survival is now handled, within limits.** While a session
  is running the app holds a foreground service, which keeps Android from
  reaping your build when you switch away. Swiping the app out of Recents
  no longer kills sessions. What it cannot override: the device-wide cap
  on background child processes, and aggressive vendor battery managers.

  That service normally shows a notification, with a **Stop all** action
  that ends every session. In this edition you may have to turn it on
  yourself, in Android's app settings for Conquest Code: the edition
  targets an old API level so that the userland can run at all, and
  Android will not show the notification permission prompt to an app that
  does. Until you allow notifications, the service protects your sessions
  without showing anything.

## Removing it

Deleting the userland frees its disk space and loses everything you
installed into it — your projects are not part of it and are untouched.
