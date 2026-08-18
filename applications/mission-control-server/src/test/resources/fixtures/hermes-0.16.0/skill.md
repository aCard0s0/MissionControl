---
name: apple-services
description: "Use when working with Apple ecosystem services from Hermes: Notes, Reminders, Messages, Find My, and macOS GUI automation."
version: 1.0.0
author: Hermes Agent
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [umbrella, consolidated, apple]
    related_skills: [apple-notes, apple-reminders, findmy, imessage, macos-computer-use]
---

# Apple Services

## Overview
Use this umbrella for Apple ecosystem tasks that require macOS-local CLIs, AppleScript, or desktop automation. Verify the Mac is available, confirm required permissions, prefer purpose-built CLIs, and fall back to UI automation only when no supported API exists.

## When to Use
- Apple Notes, Reminders, Messages/iMessage/SMS, Find My devices/AirTags, or background macOS GUI automation.

## Service Playbooks
### Notes via `memo`
Install with `brew tap antoniorodr/memo && brew install antoniorodr/memo/memo`. Use `memo notes`, search, create, and edit commands when the user explicitly wants Notes.app.

### Reminders via `remindctl`
Install with `brew install steipete/tap/remindctl`; authorize with `remindctl authorize`. Use for tasks that should sync to Apple Reminders, not Hermes cron alerts.

### Messages via `imsg`
Install with `brew install steipete/tap/imsg`; requires Messages.app signed in plus Full Disk Access/Automation permissions. Resolve chats/recipients before sending. Confirm bulk or sensitive sends.

### Find My via UI automation
Apple exposes no stable Find My CLI. Open FindMy.app with AppleScript, capture the screen, and inspect the UI/OCR. Treat location readings as privacy-sensitive and potentially stale.

### macOS computer use
When a `computer_use` tool is available, capture first, operate on numbered elements, and verify with a fresh capture. Automation should not steal the user cursor or Space.

## Pitfalls
1. Do not run Apple-only commands on Linux/remote hosts.
2. Do not confuse user reminders with agent scheduled alerts.
3. Do not claim message delivery/location precision without read-back.

## Verification Checklist
- [ ] Host/tool gateway is macOS-capable.
- [ ] Required CLI and permissions are present.
- [ ] Final state verified with read-back or screenshot.
