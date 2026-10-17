import concurrent.futures
import pathlib
import tempfile
import unittest

from ledger import Ledger


def pay(args):
    path, agreement, amount = args
    return Ledger(path).allocate("chain:consumer:hermes", agreement, "invoice-1", amount)


class SharedBillingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "ledger.db"
        self.ledger = Ledger(self.path)
        self.pool = "chain:consumer:hermes"
        self.ledger.initialize(self.pool, 100, 10000)

    def test_two_servers_share_one_cumulative_total(self):
        with concurrent.futures.ProcessPoolExecutor(2) as workers:
            results = list(workers.map(pay, [(self.path, "A", 7), (self.path, "B", 11)]))
        self.assertEqual(max(results), 118)
        self.assertEqual(Ledger(self.path).allocate(self.pool, "C", "i", 1), 119)

    def test_fifty_servers_and_retries(self):
        jobs = [(self.path, str(i), i + 1) for i in range(50)]
        with concurrent.futures.ProcessPoolExecutor(8) as workers:
            first = list(workers.map(pay, jobs))
            retry = list(workers.map(pay, jobs))
        self.assertEqual(first, retry)
        self.assertEqual(max(first), 100 + sum(range(1, 51)))
        self.assertEqual(len(set(first)), 50)

    def test_recovery_delta_and_rejection(self):
        self.assertEqual(self.ledger.allocate(self.pool, "A", "1", 7), 107)
        recovered = Ledger(self.path)
        self.assertEqual(recovered.allocate(self.pool, "A", "2", 11), 111)
        for invoice, amount in [("1", 8), ("3", 10), ("4", 10000)]:
            with self.assertRaises(ValueError):
                recovered.allocate(self.pool, "A", invoice, amount)
        self.assertEqual(recovered.allocate(self.pool, "A", "5", 12), 112)

    def test_base_units_preserve_precision(self):
        huge = 2 ** 100
        self.ledger.initialize("large", huge, huge + 100)
        self.assertEqual(self.ledger.allocate("large", "A", "1", 1), huge + 1)


if __name__ == "__main__":
    unittest.main()
