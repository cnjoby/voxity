"""Explicit, idempotent transition of today's published dynamic information."""

import json
from datetime import datetime
from zoneinfo import ZoneInfo

from .service import Service, ident, stamp


def reconcile_invitations(service, db, start, end):
    recovered = []
    rows = db.execute(
        "SELECT d.* FROM deliveries d JOIN posts p ON p.id=d.post_id "
        "WHERE d.type='invitation' AND p.question_status='waiting' "
        "AND p.published_at>=? AND p.published_at<? "
        "AND (d.state IN ('pending','prompted') OR "
        "(d.state='expired' AND d.reason_code='TASK_EXPIRED')) AND d.expires_at<?",
        (start, end, end),
    ).fetchall()
    for delivery in rows:
        db.execute(
            "UPDATE deliveries SET expires_at=?,state=CASE WHEN state='expired' THEN 'pending' "
            "ELSE state END,reason_code='ELIGIBLE',request_id=?,profile_revision=? WHERE id=?",
            (
                end,
                ident("r"),
                service.profile_row(db, delivery["recipient_id"])["revision"],
                delivery["id"],
            ),
        )
        recovered.append(delivery["id"])
    return recovered


def migrate_today(service):
    if not service.settings.information_valid_until_day_end:
        raise ValueError("Day-end validity must be enabled before migration")
    now = service.clock()
    local = datetime.fromtimestamp(now, ZoneInfo("Asia/Shanghai"))
    start = local.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()
    end = service.information_expiry(now, 0)
    marker = f"information_day_end_v1:{local.date()}"
    changed, reopened = [], []
    with service.store.transaction() as db:
        if db.execute("SELECT 1 FROM schema_migrations WHERE id=?", (marker,)).fetchone():
            # A previous deployment may have rolled back and issued duration-based
            # invitations between attempts. Reconcile those without reopening tasks.
            return {
                "updated_posts": [],
                "reopened_questions": [],
                "already_applied": True,
                "recovered_invitations": reconcile_invitations(service, db, start, end),
            }
        rows = db.execute(
            "SELECT * FROM posts WHERE status='published' AND published_at>=? AND published_at<?",
            (start, end),
        ).fetchall()
        for post in rows:
            meta, state = service.voice_meta(db, post["id"])
            dynamic = post["kind"] in ("question", "answer", "reply") or (
                meta
                and (post["kind"] == "announcement" or meta.get("purpose") == "observation")
                and meta.get("validity_policy") != "until_withdrawn"
            )
            if not dynamic or state and (state["withdrawn_at"] or state["replaced_by"]):
                continue
            if meta and post["kind"] in ("scene", "announcement"):
                meta["valid_until"] = stamp(end)
                db.execute(
                    "UPDATE voice_details SET metadata_json=? WHERE post_id=?",
                    (json.dumps(meta, ensure_ascii=False), post["id"]),
                )
            db.execute("UPDATE posts SET expires_at=? WHERE id=?", (end, post["id"]))
            changed.append(post["id"])
            # Restore only the author's latest expired question, never cancelled,
            # answered or superseded requests, and never a playback receipt.
            if post["kind"] == "question" and post["question_status"] == "expired":
                latest = db.execute(
                    "SELECT id FROM posts WHERE author_id=? AND kind='question' "
                    "AND status='published' ORDER BY published_at DESC,id DESC LIMIT 1",
                    (post["author_id"],),
                ).fetchone()
                waiting = db.execute(
                    "SELECT id FROM posts WHERE author_id=? AND question_status='waiting'",
                    (post["author_id"],),
                ).fetchone()
                if latest["id"] == post["id"] and not waiting:
                    db.execute(
                        "UPDATE posts SET question_status='waiting' WHERE id=?", (post["id"],)
                    )
                    db.execute(
                        "UPDATE personal_tasks SET status='waiting',reason_code=NULL,expires_at=?,"
                        "updated_at=?,revision=revision+1 WHERE question_id=? AND status='timed_out'",
                        (end, now, post["id"]),
                    )
                    reopened.append(post["id"])
            db.execute(
                "UPDATE deliveries SET expires_at=? WHERE post_id=? AND type='audio' "
                "AND state IN ('pending','prompted')",
                (end, post["id"]),
            )
            db.execute(
                "UPDATE personal_tasks SET expires_at=? WHERE question_id=? "
                "AND status IN ('waiting','responded')",
                (end, post["id"]),
            )
        recovered = reconcile_invitations(service, db, start, end)
        db.execute("INSERT INTO schema_migrations VALUES (?,?)", (marker, now))
    return {
        "updated_posts": changed,
        "reopened_questions": reopened,
        "valid_until": stamp(end),
        "recovered_invitations": recovered,
    }


if __name__ == "__main__":
    from .config import Settings

    print(json.dumps(migrate_today(Service(Settings())), ensure_ascii=False))
