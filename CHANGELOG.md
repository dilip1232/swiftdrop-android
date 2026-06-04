# Changelog

All notable changes to SwiftDrop Android will be documented in this file.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0]

### Added
- **Folder transfer** — pick and send entire folders; files stream in parallel and the original directory structure is reconstructed on the receiver
- **Partial transfer status** — cancelled folder transfers show exactly how many files were received instead of a generic error
- **Accept/Reject in transfer list** — respond to incoming transfers directly from the app page, in addition to the notification and popup dialog
- **Folder and file icons** — transfer list shows distinct icons for folders and files

### Improved
- **Sticky consent notification** — the accept/reject notification can no longer be accidentally dismissed; you must tap Accept or Reject
- **Consent notification auto-dismiss** — responding from the app page automatically clears the notification

## [1.1.0]

### Added
- **SPAKE2 PIN pairing** — secure device pairing where the PIN never leaves your device
- **QR code pairing** — scan a QR code to pair devices instantly
- **Per-device chat** — send text messages to individual paired devices
- **Receiver consent** — incoming transfers require approval before files are written
- **Pause and resume** — pause in-flight transfers and resume them later
- **Encrypted transfers** — all transfers between paired devices are encrypted end-to-end

## [1.0.0]

### Added
- **Device pairing** — pair devices securely before transferring
- **Cancel and retry** — cancel in-flight sends and retry failed ones
- **Share sheet integration** — share files into SwiftDrop from any app

## [0.1.0] - 2025-06-02

### Added
- Initial release
- Wireless file transfer between Mac and Android over local network
- mDNS auto-discovery of nearby devices
- End-to-end encrypted transfers
- Background transfer service with notifications
- Web-based UI served locally
