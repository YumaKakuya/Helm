# MCPHUB Packaging

This directory contains optional user-scoped service integration templates used by `install.sh`.

## Layout

- `packaging/systemd/mcphub.service` — systemd **user** service unit template (Linux/WSL2)
- `packaging/launchd/dev.sorted.mcphub.plist` — launchd LaunchAgent template (macOS)

Both templates use `{{MCPHUB_INSTALL_PATH}}`, replaced by `install.sh` with the installed `mcphub` binary path.

## Install

From repository root:

- user-scoped install only: `./install.sh`
- install + service integration: `./install.sh --service`
- custom prefix: `./install.sh --prefix /path/to/install`

## Uninstall

- `./uninstall.sh`

This removes MCPHUB binary, user service integration files, and install/data directory.
