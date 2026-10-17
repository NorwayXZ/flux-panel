"""Offline payment-allocation prototype, not a Mysterium payment service.

No keys, HTTP listener, network payments, or production node changes are involved.
Amounts use integer base units. A real integration must verify invoice signatures,
authorize nodes, sign centrally and confirm Hermes acceptance before enabling use.
"""

import sqlite3


class Ledger:
    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS pools (
                    id TEXT PRIMARY KEY, total TEXT NOT NULL, ceiling TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS agreements (
                    pool TEXT NOT NULL, id TEXT NOT NULL, billed TEXT NOT NULL,
                    PRIMARY KEY (pool, id)
                );
                CREATE TABLE IF NOT EXISTS invoices (
                    pool TEXT NOT NULL, agreement TEXT NOT NULL, invoice TEXT NOT NULL,
                    billed TEXT NOT NULL, allocated TEXT NOT NULL,
                    PRIMARY KEY (pool, agreement, invoice)
                );
            """)

    def connect(self):
        return sqlite3.connect(self.path, timeout=30)

    def initialize(self, pool, recovered_total, ceiling):
        if not 0 <= recovered_total <= ceiling:
            raise ValueError("invalid recovered total or ceiling")
        with self.connect() as db:
            db.execute("INSERT INTO pools VALUES (?, ?, ?)",
                       (pool, str(recovered_total), str(ceiling)))

    def allocate(self, pool, agreement, invoice, billed):
        if not isinstance(billed, int) or isinstance(billed, bool) or billed < 0:
            raise ValueError("billed must be a nonnegative integer")
        with self.connect() as db:
            # Serialize the read/modify/write across processes, not just threads.
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute(
                "SELECT billed, allocated FROM invoices WHERE pool=? AND agreement=? AND invoice=?",
                (pool, agreement, invoice)).fetchone()
            if previous:
                if int(previous[0]) != billed:
                    raise ValueError("invoice replay changed its amount")
                return int(previous[1])
            state = db.execute("SELECT total, ceiling FROM pools WHERE id=?", (pool,)).fetchone()
            if state is None:
                raise ValueError("pool must be reconciled before allocating")
            last = db.execute("SELECT billed FROM agreements WHERE pool=? AND id=?",
                              (pool, agreement)).fetchone()
            delta = billed - (int(last[0]) if last else 0)
            if delta < 0:
                raise ValueError("agreement total decreased")
            total = int(state[0]) + delta
            if total > int(state[1]):
                raise ValueError("pool ceiling exceeded")
            db.execute("UPDATE pools SET total=? WHERE id=?", (str(total), pool))
            db.execute("INSERT INTO agreements VALUES (?, ?, ?) ON CONFLICT(pool,id) "
                       "DO UPDATE SET billed=excluded.billed", (pool, agreement, str(billed)))
            db.execute("INSERT INTO invoices VALUES (?, ?, ?, ?, ?)",
                       (pool, agreement, invoice, str(billed), str(total)))
            return total
