"""Bus d'événements interne (thread-safe) : ingestion -> agents Overmind, WebSocket, journaux.

Les abonnés reçoivent des dictionnaires `{"type": ..., "vehicle_id": ..., ...}`. Le bus est
strictement descendant vers Overmind : rien de ce qui y circule ne repart vers la passerelle.
"""

from __future__ import annotations

import asyncio
import logging
import threading
from typing import Any, Callable

log = logging.getLogger("overmind_obd.bus")
Event = dict[str, Any]


class EventBus:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._callbacks: list[Callable[[Event], None]] = []
        self._queues: list[tuple[asyncio.AbstractEventLoop, asyncio.Queue]] = []

    def subscribe(self, cb: Callable[[Event], None]) -> Callable[[], None]:
        with self._lock:
            self._callbacks.append(cb)

        def _unsub() -> None:
            with self._lock:
                if cb in self._callbacks:
                    self._callbacks.remove(cb)
        return _unsub

    def subscribe_queue(self, loop: asyncio.AbstractEventLoop, maxsize: int = 500) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=maxsize)
        with self._lock:
            self._queues.append((loop, q))
        return q

    def unsubscribe_queue(self, q: asyncio.Queue) -> None:
        with self._lock:
            self._queues = [(lp, qq) for lp, qq in self._queues if qq is not q]

    def publish(self, event: Event) -> None:
        with self._lock:
            callbacks = list(self._callbacks)
            queues = list(self._queues)
        for cb in callbacks:
            try:
                cb(event)
            except Exception:  # un abonné défaillant ne bloque pas l'ingestion
                log.exception("abonné du bus en erreur")
        for loop, q in queues:
            def _put(q: asyncio.Queue = q) -> None:
                if q.full():  # client lent : on jette le plus ancien
                    try:
                        q.get_nowait()
                    except asyncio.QueueEmpty:
                        pass
                q.put_nowait(event)
            try:
                loop.call_soon_threadsafe(_put)
            except RuntimeError:  # boucle fermée
                self.unsubscribe_queue(q)
