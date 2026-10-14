#!/usr/bin/env python3
# /// script
# dependencies = ["google-auth[requests]>=2"]
# ///
"""Send an announcement to app installs: uv run tools/push-announce.py --key SA.json --topic all --title T

A data-only FCM message in the shape `PushMessage.fromData` reads (services/mobile/telemetry): the
app shows it as a notification and once more as an in-app dialog. Only installs that agreed to
telemetry are subscribed. Topics: `all`, `channel_<release|canary|debug>`, `model_<Build.MODEL,
characters a topic disallows as _>`. `--dry-run` asks FCM to validate without delivering.
`--notification` sends a plain notification message instead, which every build shows, including
those before announcements existed (0.2.0-beta.8 and older); a tap only opens the app, so it takes
no link or action and has no in-app copy.
The service-account key is a secret: keep it outside the repo.
"""
import argparse
import json
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--key", required=True, help="Firebase service-account JSON")
    ap.add_argument("--topic", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--body", default="")
    ap.add_argument("--link", help="https URL opened on tap and by the action button")
    ap.add_argument("--action", help="label of the button that opens --link")
    ap.add_argument("--image", help="https URL of an image shown beside the text")
    ap.add_argument("--notification", action="store_true", help="plain notification message, shown by every build")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if a.notification and (a.link or a.action):
        ap.error("--notification cannot carry --link or --action: a tap only opens the app")

    for name in ("link", "image"):
        url = getattr(a, name)
        if url and not url.startswith("https://"):
            ap.error(f"--{name} must be https (the app drops anything else)")

    message = {
        "topic": a.topic,
        # High priority so a dozing device wakes the app's service to draw it.
        "android": {"priority": "high"},
    }
    if a.notification:
        message["notification"] = {k: v for k, v in {"title": a.title, "body": a.body, "image": a.image}.items() if v}
    else:
        data = {"title": a.title, "body": a.body, "link": a.link, "action": a.action, "image": a.image}
        message["data"] = {k: v for k, v in data.items() if v}
    creds = service_account.Credentials.from_service_account_file(a.key, scopes=[SCOPE])
    url = f"https://fcm.googleapis.com/v1/projects/{creds.project_id}/messages:send"
    resp = AuthorizedSession(creds).post(url, json={"message": message, "validate_only": a.dry_run})
    print(json.dumps(resp.json(), indent=2))
    return 0 if resp.ok else 1


if __name__ == "__main__":
    sys.exit(main())
