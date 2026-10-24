"""Single-process, bounded personal attention worker. No network inside SQLite scopes."""

import asyncio
import logging

from .decision import Attention

logger = logging.getLogger(__name__)


class AttentionWorker:
    def __init__(self, service):
        self.service = service

    async def once(self):
        snapshot = await asyncio.to_thread(self.service.next_attention)
        if snapshot is None:
            return False
        try:
            result = await asyncio.wait_for(
                self.service.decider.evaluate_async(
                    snapshot["focus"], snapshot["transcript"], context=snapshot["context"]
                ),
                timeout=self.service.settings.decision_timeout,
            )
        except TimeoutError:
            result = Attention("defer", "MODEL_TIMEOUT")
        except asyncio.CancelledError:
            raise
        except Exception:
            # Do not log secret-bearing upstream request/response data.
            result = Attention("defer", "MODEL_UNAVAILABLE")
        await asyncio.to_thread(self.service.finish_attention, snapshot, result)
        return True

    async def run(self):
        while True:
            try:
                worked = await self.once()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.error("Attention worker database step failed; will retry")
                worked = False
            if not worked:
                await asyncio.sleep(0.25)
