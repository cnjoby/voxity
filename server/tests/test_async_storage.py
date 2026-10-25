import asyncio
import time

import pytest

from app.storage import Database


def test_writer_wait_keeps_loop_free_to_release_another_request(tmp_path):
    store = Database(tmp_path)

    async def check():
        writer = store.connect()
        writer.execute("BEGIN IMMEDIATE")

        async def finish_other_request():
            await asyncio.sleep(0.03)
            writer.commit()
            writer.close()

        release = asyncio.create_task(finish_other_request())
        start = time.monotonic()
        async with store.async_transaction() as db:
            assert db.execute("SELECT 1").fetchone()[0] == 1
        await release
        assert time.monotonic() - start < 1

    asyncio.run(check())


def test_cancelled_waiter_does_not_leave_a_writer_open(tmp_path):
    store = Database(tmp_path)

    async def check():
        writer = store.connect()
        writer.execute("BEGIN IMMEDIATE")

        async def request():
            async with store.async_transaction():
                pytest.fail("Cancelled request must not enter its business operation")

        task = asyncio.create_task(request())
        await asyncio.sleep(0.03)
        task.cancel()
        await asyncio.sleep(0.02)
        writer.commit()
        writer.close()
        with pytest.raises(asyncio.CancelledError):
            await task
        async with store.async_transaction() as db:
            assert db.execute("SELECT 1").fetchone()[0] == 1

    asyncio.run(check())
