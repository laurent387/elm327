#!/usr/bin/env python3
"""Broker MQTT de DÉVELOPPEMENT (amqtt, anonyme, sans TLS) pour les tests locaux.

    overmind/.venv/bin/python tools/dev_broker.py --port 1884

Ne jamais l'exposer : en production, utiliser Mosquitto (deploy/mosquitto/) avec
mots de passe, ACL par passerelle et TLS.
"""
import argparse
import asyncio
import logging


async def main(host: str, port: int) -> None:
    from amqtt.broker import Broker

    cfg = {
        "listeners": {"default": {"type": "tcp", "bind": f"{host}:{port}"}},
        "plugins": {"amqtt.plugins.authentication.AnonymousAuthPlugin": {"allow_anonymous": True}},
    }
    broker = Broker(cfg)
    await broker.start()
    print(f"broker de dev prêt sur {host}:{port}", flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1884)
    a = ap.parse_args()
    logging.basicConfig(level=logging.WARNING)
    asyncio.run(main(a.host, a.port))
