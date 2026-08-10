# services/shared

Contracts shared between `services/mobile` and `services/backend` once both exist: API schemas (OpenAPI/protobuf), shared constants, versioned data models. Nothing here should depend on either service — dependencies flow inward, `mobile` and `backend` both depend on `shared`, never the reverse.

Keep as a single flat folder until it's large enough to need subdivision (e.g. `shared/api-contracts`, `shared/proto`) — don't pre-split before there's real content driving the split.
