"""compare.py --fail-if-worse gate: python3 -m unittest discover -s tools/soak/tests -v (from the repository root)."""
import contextlib
import importlib.util
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path

SOAK = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('soak_compare_gate', SOAK / 'compare.py')
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)

# Three seeds shaped like the master baseline: deaths 6, births 7, builtBlocks 438, newBeds 0 in total.
BASE = {'8675309-normal': (2, 1, 156, 0), '42-normal': (2, 3, 260, 0), '1337-normal': (2, 3, 22, 0)}


def result(deaths, births, built, beds, completed=True, **extra):
    outcome = {'births': births, 'deaths': deaths, 'newBeds': beds, 'builtBlocks': built, **extra}
    if beds is None:
        del outcome['newBeds']
    return {'completed': completed, 'reason': 'completed' if completed else 'real_time_limit_run', 'start': {'villagers': 6},
            'end': {'villagers': 6 + births - deaths}, 'outcome': outcome}


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix='soak-gate-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def batch(self, name, runs):
        for label, data in runs.items():
            (self.tmp / name / label).mkdir(parents=True, exist_ok=True)
            (self.tmp / name / label / 'soak-result.json').write_text(json.dumps(data))
        return str(self.tmp / name)

    def base(self, **extra):
        return self.batch('base', {label: result(*v, **extra) for label, v in BASE.items()})

    def cand(self, change=None, **extra):
        runs = {label: result(*v, **extra) for label, v in BASE.items()}
        runs.update(change or {})
        return self.batch('cand', runs)

    def run_main(self, base, cand, *flags):
        """(exit code, stdout, stderr) of compare.main."""
        out, err, code = io.StringIO(), io.StringIO(), 0
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                compare.main(['--base', base, '--candidate', cand, '--base-name', 'master', '--candidate-name', 'rc', *flags])
            except SystemExit as stop:
                code = stop.code if isinstance(stop.code, int) else 1
        return code, out.getvalue(), err.getvalue()

    def row(self, text, key):
        return next(line for line in text.splitlines() if f'(`{key}`)' in line)

    def test_identical_batches_pass(self):
        code, text, _ = self.run_main(self.base(), self.cand(), '--fail-if-worse')
        self.assertEqual(code, 0)
        self.assertIn('| метрика | rc | master | допуск | вердикт |', text)
        self.assertEqual(self.row(text, 'deaths'), '| смерти (`deaths`) | 6 | 6 | ≤ 8 (+max(1, 20 %)) | OK |')
        self.assertEqual(self.row(text, 'births'), '| рождения (`births`) | 7 | 7 | ≥ 5 (−max(1, 20 %)) | OK |')
        self.assertEqual(self.row(text, 'builtBlocks'), '| построено блоков (`builtBlocks`) | 438 | 438 | ≥ 394 (−10 %) | OK |')
        self.assertEqual(self.row(text, 'newBeds'), '| новых кроватей (`newBeds`) | 0 | 0 | ≥ 0 (−0) | OK |')
        self.assertIn('**Вердикт: не хуже базы.**', text)
        self.assertIn('Допуски от суммы базы по общим завершённым прогонам: deaths +max(1, 20 %), births −max(1, 20 %), '
                      'builtBlocks −10 %, newBeds −0', text)

    def test_deaths_within_and_beyond_tolerance(self):
        code, text, _ = self.run_main(self.base(), self.cand({'42-normal': result(4, 3, 260, 0)}), '--fail-if-worse')
        self.assertEqual((code, self.row(text, 'deaths').endswith('| OK |')), (0, True))
        code, text, err = self.run_main(self.base(), self.cand({'42-normal': result(5, 3, 260, 0)}), '--fail-if-worse')
        self.assertEqual(code, 2)
        self.assertEqual(self.row(text, 'deaths'), '| смерти (`deaths`) | 9 | 6 | ≤ 8 (+max(1, 20 %)) | ХУЖЕ |')
        self.assertIn('worse than base: deaths', err)

    def test_built_blocks_down_twenty_percent_fails(self):
        cand = self.cand({label: result(d, b, int(built * 0.8), beds) for label, (d, b, built, beds) in BASE.items()})
        code, text, _ = self.run_main(self.base(), cand, '--fail-if-worse')
        self.assertEqual(code, 2)
        self.assertTrue(self.row(text, 'builtBlocks').endswith('| ХУЖЕ |'))
        self.assertIn('**Вердикт: хуже базы** (builtBlocks).', text)

    def test_tolerance_rounds_up_in_favour_of_the_candidate(self):
        _, worse = compare.verdict(compare.runs([self.base()]), compare.runs([self.cand({'1337-normal': result(2, 3, -22, 0)})]), list(BASE))
        self.assertEqual(worse, [])  # 394 >= 438 - ceil(43.8)
        _, worse = compare.verdict(compare.runs([self.base()]), compare.runs([self.cand({'1337-normal': result(2, 3, -23, 0)})]), list(BASE))
        self.assertEqual(worse, ['builtBlocks'])
        self.assertEqual(compare.allowance(438, (0, 10)), 44)
        self.assertEqual(compare.allowance(30, (0, 10)), 3)
        self.assertEqual(compare.allowance(0, (1, 20)), 1)
        self.assertEqual(compare.allowance(-3, (0, 50)), 2)

    def test_failed_candidate_run_is_left_out_on_both_sides(self):
        base = self.batch('base', {**{label: result(*v) for label, v in BASE.items()}, '1337-normal': result(10, 0, 0, 0)})
        cand = self.cand({'1337-normal': {'completed': False, 'reason': 'real_time_limit_run'}})
        code, text, _ = self.run_main(base, cand, '--fail-if-worse')
        self.assertEqual(code, 0)
        self.assertEqual(self.row(text, 'deaths'), '| смерти (`deaths`) | 4 | 4 | ≤ 5 (+max(1, 20 %)) | OK |')
        self.assertIn('Не вошли в итог: 1337-normal (rc: не завершён: real_time_limit_run)', text)

    def test_tolerance_option_is_parsed_and_applied(self):
        cand = self.cand({'42-normal': result(3, 3, 250, 0)})
        code, text, _ = self.run_main(self.base(), cand, '--fail-if-worse', '--tolerance', 'deaths=0', 'builtBlocks=5%')
        self.assertEqual(code, 2)
        self.assertEqual(self.row(text, 'deaths'), '| смерти (`deaths`) | 7 | 6 | ≤ 6 (+0) | ХУЖЕ |')
        self.assertEqual(self.row(text, 'builtBlocks'), '| построено блоков (`builtBlocks`) | 428 | 438 | ≥ 416 (−5 %) | OK |')
        self.assertIn('deaths +0, births −max(1, 20 %), builtBlocks −5 %, newBeds −0', text)
        self.assertEqual(compare.parse_tolerance('births=max(2, 30 %)'), ('births', (2.0, 30.0)))
        for bad in ('kills=1', 'deaths', 'deaths=many'):
            with self.assertRaises(ValueError):
                compare.parse_tolerance(bad)
        code, _, err = self.run_main(self.base(), cand, '--tolerance', 'kills=1')
        self.assertEqual(code, 2)
        self.assertIn('kills', err)

    def test_without_flag_worse_still_exits_zero(self):
        code, text, _ = self.run_main(self.base(), self.cand({'42-normal': result(2, 3, 26, 0)}))
        self.assertEqual(code, 0)
        self.assertIn('| ХУЖЕ |', text)
        self.assertIn('**Вердикт: хуже базы** (builtBlocks).', text)

    def test_information_columns_show_dash_and_never_decide(self):
        cand = self.cand(longestStallMinutes=600, changeShare=0.0, routeWaitShare=0.99)
        code, text, _ = self.run_main(self.base(), cand, '--fail-if-worse')
        self.assertEqual(code, 0)
        self.assertIn('| самый долгий простой, мин (`longestStallMinutes`), среднее | 600 | – | – | справочно |', text)
        self.assertIn('| самый долгий дневной простой, мин (`longestDayStallMinutes`), среднее | – | – | – | справочно |', text)
        self.assertIn('| доля строк с изменениями (`changeShare`), среднее | 0 | – | – | справочно |', text)
        self.assertIn('| доля ожидания маршрута (`routeWaitShare`), среднее | 0.99 | – | – | справочно |', text)
        self.assertIn('**Вердикт: не хуже базы.**', text)

    def test_metric_missing_in_a_run_is_no_data_not_worse(self):
        cand = self.batch('cand', {label: result(d, b, built, None) for label, (d, b, built, _) in BASE.items()})
        code, text, _ = self.run_main(self.base(), cand, '--fail-if-worse')
        self.assertEqual(code, 0)
        self.assertEqual(self.row(text, 'newBeds'), '| новых кроватей (`newBeds`) | – | 0 | −0 | нет данных |')

    def test_nothing_comparable_exits_one_even_with_the_gate(self):
        base = self.batch('base', {'1-normal': result(0, 1, 10, 0)})
        cand = self.batch('cand', {'2-normal': result(9, 0, 0, 0)})
        code, text, err = self.run_main(base, cand, '--fail-if-worse')
        self.assertEqual(code, 1)
        self.assertNotIn('Вердикт', text)


if __name__ == '__main__':
    unittest.main()
