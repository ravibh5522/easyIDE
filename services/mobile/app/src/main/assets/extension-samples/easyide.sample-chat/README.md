# Chat (sample)

A notes-style chat kept on the device: rooms of messages in the extension's own storage (`storage.get` / `storage.set`, global
scope, so it works from Home as well as inside a project). The logic is a small WebAssembly module (source in
`services/shared/samples/chat-guest-as`); everything on screen is a view file.

- Navigation item **Chat** in both scopes: the bottom bar on a phone, the rail on a tablet.
- **Rooms** list, and a field to open a new room by name. A room opens as a document with the `chat` and `composer` components.
- The module pushes the room list to the view with `ui.setViewData`; a room's messages come back as the result of the
  `load` and `send` commands and are merged into the document (`"as": "."`).
- Capability: `ui.contribute` only. No processes, no network, no files.
