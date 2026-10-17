import unittest
from validation_timing import colony_timeout_seconds

class TimingTests(unittest.TestCase):
    def test_acceleration_reduces_watchdog_but_retains_startup_and_lag_margin(self):
        self.assertEqual(1260,colony_timeout_seconds(144000,400))
        self.assertGreater(colony_timeout_seconds(144000,400),144000/400)
        self.assertGreater(colony_timeout_seconds(144000,40),colony_timeout_seconds(144000,400))
    def test_large_duration_does_not_create_an_unbounded_runner(self):
        self.assertEqual(5100,colony_timeout_seconds(144000,1))
    def test_invalid_rates_are_rejected_before_launch(self):
        for rate in (0,-1,float('nan'),float('inf')):
            with self.assertRaises(ValueError):colony_timeout_seconds(24000,rate)
