"""Soak tooling tests: python3 -m unittest discover -s tools/soak/tests -v (from the repository root)."""
import contextlib
import importlib.util
import io
import json
import re
import shutil
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest import mock

SOAK = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / 'fixtures'


def script(name):
    spec = importlib.util.spec_from_file_location(f'soak_{name}', SOAK / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


run, compare, digest, soakmap = script('run'), script('compare'), script('digest'), script('map')


def jar_bytes():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as z:
        z.writestr('fabric.mod.json', '{"id": "x"}' + ' ' * 2000)
    return buffer.getvalue()


def quiet(function, *args):
    """Runs function, returning (result, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        result = function(*args)
    return result, out.getvalue(), err.getvalue()


class TempDir(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix='soak-test-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)


class JarSelectionTest(TempDir):
    NAV_JAR = Path('/nav/build/libs/navigation-ai-0.2.2.jar')
    API_JAR = Path('/template/fabric-api-0.161.0+26.2.jar')

    def names(self, primary):
        return sorted(j.name for j in run.select_jars([Path('/rel') / n for n in primary], [self.NAV_JAR, self.API_JAR]))

    def test_artifact_strips_version(self):
        self.assertEqual(run.artifact('navigation-ai-0.2.2.jar'), 'navigation-ai')
        self.assertEqual(run.artifact('autonomous-villages-soak-0.3.0-dev.jar'), 'autonomous-villages-soak')
        self.assertEqual(run.artifact('autonomous-villages-0.2.99-master.jar'), 'autonomous-villages')
        self.assertEqual(run.artifact('fabric-api-0.161.0+26.2.jar'), 'fabric-api')
        self.assertEqual(run.artifact('Navigation-AI.jar'), 'navigation-ai')

    def test_folder_library_wins_and_is_installed_once(self):
        names = self.names(['autonomous-villages-0.2.2.jar', 'autonomous-villages-soak-0.2.2.jar', 'navigation-ai-0.2.1.jar',
                            'autonomous-villages-0.2.2-sources.jar'])
        self.assertEqual(names, ['autonomous-villages-0.2.2.jar', 'autonomous-villages-soak-0.2.2.jar', 'fabric-api-0.161.0+26.2.jar',
                                 'navigation-ai-0.2.1.jar'])

    def test_build_library_used_when_folder_has_none(self):
        self.assertIn('navigation-ai-0.2.2.jar', self.names(['autonomous-villages-0.2.2.jar', 'autonomous-villages-soak-0.2.2.jar']))

    def test_folder_fabric_api_wins(self):
        names = self.names(['autonomous-villages-0.2.2.jar', 'fabric-api-0.150.0+26.2.jar'])
        self.assertEqual([n for n in names if n.startswith('fabric-api')], ['fabric-api-0.150.0+26.2.jar'])

    def test_two_versions_of_one_mod_in_folder_are_refused(self):
        with self.assertRaisesRegex(ValueError, 'navigation-ai'):
            self.names(['navigation-ai-0.2.1.jar', 'navigation-ai-0.2.2.jar'])

    def test_snapshot_copies_exactly_one_library(self):
        folder, nav, template, batch = self.tmp / 'release', self.tmp / 'nav', self.tmp / 'template', self.tmp / 'batch'
        for path in [folder / 'autonomous-villages-0.2.2.jar', folder / 'autonomous-villages-soak-0.2.2.jar', folder / 'navigation-ai-0.2.1.jar',
                     nav / 'build/libs/navigation-ai-0.2.2.jar', template / f'fabric-api-{run.FABRIC_API}.jar']:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(jar_bytes())
        (nav / 'gradle.properties').write_text('version=0.2.2\n')
        with mock.patch.object(run, 'NAV', nav), mock.patch.object(run, 'TEMPLATE', template):
            jars = run.snapshot_jars(batch, folder, '0.2.2')
            self.assertEqual(sorted(p.name for p in jars.glob('navigation-ai*.jar')), ['navigation-ai-0.2.1.jar'])
            self.assertEqual(len(list(jars.glob('*.jar'))), 4)
            (folder / 'navigation-ai-0.2.1.jar').unlink()
            jars = run.snapshot_jars(batch, folder, '0.2.2')
            self.assertEqual(sorted(p.name for p in jars.glob('navigation-ai*.jar')), ['navigation-ai-0.2.2.jar'])
            with self.assertRaisesRegex(FileNotFoundError, 'autonomous-villages-0.9.jar'):
                run.snapshot_jars(batch, None, '0.9')


class DownloadTest(TempDir):
    def fake(self, data, error=None):
        def retrieve(url, path):
            Path(path).write_bytes(data)
            if error:
                raise error
        return retrieve

    def test_complete_download_is_renamed(self):
        target = self.tmp / 'fabric-server-launch.jar'
        with mock.patch('urllib.request.urlretrieve', side_effect=self.fake(jar_bytes())) as retrieve:
            run.download('https://example.invalid/x.jar', target)
        self.assertEqual(retrieve.call_args[0][1], self.tmp / 'fabric-server-launch.jar.part')
        self.assertTrue(run.valid_jar(target))
        self.assertEqual(list(self.tmp.iterdir()), [target])

    def test_truncated_download_never_becomes_the_jar(self):
        target = self.tmp / 'fabric-server-launch.jar'
        with mock.patch('urllib.request.urlretrieve', side_effect=self.fake(jar_bytes()[:-30])):
            with self.assertRaisesRegex(RuntimeError, 'not a complete jar'):
                run.download('https://example.invalid/x.jar', target)
        self.assertEqual(list(self.tmp.iterdir()), [])

    def test_interrupted_download_leaves_nothing(self):
        target = self.tmp / 'api.jar'
        with mock.patch('urllib.request.urlretrieve', side_effect=self.fake(b'PK\x03\x04half', urllib.error.URLError('reset'))):
            with self.assertRaises(urllib.error.URLError):
                run.download('https://example.invalid/x.jar', target)
        self.assertEqual(list(self.tmp.iterdir()), [])

    def test_truncated_jar_from_an_old_run_is_replaced(self):
        target = self.tmp / 'api.jar'
        target.write_bytes(jar_bytes()[:100])
        self.assertFalse(run.valid_jar(target))
        with mock.patch('urllib.request.urlretrieve', side_effect=self.fake(jar_bytes())):
            run.download('https://example.invalid/x.jar', target)
        self.assertTrue(run.valid_jar(target))

    def test_cached_copy_goes_through_the_same_check(self):
        cached, target = self.tmp / 'cached.jar', self.tmp / 'api.jar'
        cached.write_bytes(jar_bytes())
        with mock.patch('urllib.request.urlretrieve') as retrieve:
            run.download('https://example.invalid/x.jar', target, cached)
        retrieve.assert_not_called()
        self.assertTrue(run.valid_jar(target))

    def test_template_with_truncated_launcher_is_not_ready(self):
        template = self.tmp / 'template'
        for name in ['libraries', 'versions', '.fabric']:
            (template / name).mkdir(parents=True)
        (template / 'fabric-server-launch.jar').write_bytes(jar_bytes()[:50])
        (template / f'fabric-api-{run.FABRIC_API}.jar').write_bytes(jar_bytes())

        def boot(cmd, cwd, **kwargs):
            for name in ['libraries', 'versions', '.fabric']:
                (Path(cwd) / name).mkdir()
        with mock.patch.object(run, 'TEMPLATE', template), mock.patch('urllib.request.urlretrieve', side_effect=self.fake(jar_bytes())) as retrieve, \
                mock.patch.object(run.subprocess, 'run', side_effect=boot) as server:
            run.ensure_template(Path('/no/java'))
            self.assertEqual(retrieve.call_count, 1)
            self.assertEqual(server.call_count, 1)
            self.assertTrue(run.valid_jar(template / 'fabric-server-launch.jar'))
            run.ensure_template(Path('/no/java'))
            self.assertEqual((retrieve.call_count, server.call_count), (1, 1))


class RunnerTest(TempDir):
    def test_exception_fails_only_its_run(self):
        for parallel in (1, 3):
            def job(seed, difficulty):
                if seed == '42':
                    raise OSError('disk full')
                return f'{seed}-{difficulty}', {'completed': True, 'reason': 'completed'}
            jobs = [('1337', 'normal'), ('42', 'normal'), ('8675309', 'normal'), ('7', 'peaceful')]
            results, out, err = quiet(run.run_jobs, jobs, parallel, job)
            self.assertEqual(sorted(results), ['1337-normal', '42-normal', '7-peaceful', '8675309-normal'])
            self.assertEqual(results['42-normal']['error'], 'OSError: disk full')
            self.assertIn('error=OSError: disk full', out)
            self.assertIn('Traceback', err)
            self.assertEqual(run.failed_runs(results), ['42-normal'])

    def test_failed_reporting_does_not_lose_the_rest(self):
        def report(label, result):
            raise TypeError('bad row')
        results, _, err = quiet(run.run_jobs, [('1', 'normal'), ('2', 'normal')], 1, lambda s, d: (f'{s}-{d}', {'completed': True}), report)
        self.assertEqual(run.failed_runs(results), [])
        self.assertEqual(len(results), 2)
        self.assertIn('bad row', err)

    def test_incomplete_and_missing_result_runs_fail(self):
        results = {'a-normal': {'completed': True}, 'b-normal': {'completed': False, 'reason': 'no_result_file'},
                   'c-normal': {'completed': False, 'reason': 'real_time_limit_run'}}
        self.assertEqual(run.failed_runs(results), ['b-normal', 'c-normal'])

    def test_log_digest_keeps_probe_lines_and_counts_past_the_cap(self):
        lines = [b'[10:00:00] [Server thread/INFO] (STDOUT) SOAK village at 1, 2, 3',
                 b'[10:00:01] [Server thread/INFO]: DEATH Soak Anna: zombie',
                 b'[10:00:02] [Server thread/INFO]: Preparing spawn area',
                 b'[10:00:03] [Server thread/INFO] (STDOUT) SOAK day=0.05 tps=99.0 villagers=6'] + \
                [f'[10:01:{i:02}] [Server thread/ERROR]: tick failed {i}'.encode() for i in range(250)]
        events, problems, total = run.log_digest(lines)
        self.assertEqual(len(events), 3)
        self.assertTrue(all(b'SOAK ' in e or b'DEATH ' in e for e in events))
        self.assertEqual((len(problems), total), (200, 250))

    def test_row_tolerates_nulls_and_shows_cap(self):
        line = run.row('42-normal', {'completed': True, 'gameDays': None, 'logErrors': 200, 'logErrorsTotal': 250})
        self.assertIn('cap hit: 250', line)
        self.assertIn('days=None', line)

    def test_main_exits_nonzero_and_records_errors(self):
        def one(java, batch_dir, seed, difficulty, args, jars):
            if seed == '42':
                raise RuntimeError('server.log missing')
            if seed == '7':
                return f'{seed}-{difficulty}', {'completed': False, 'reason': 'no_result_file'}
            return f'{seed}-{difficulty}', {'completed': True, 'reason': 'completed', 'outcome': {'births': 1}}
        with mock.patch.object(run, 'ROOT', self.tmp), mock.patch.object(run, 'java_home', return_value=Path('/no/java')), \
                mock.patch.object(run, 'ensure_template'), mock.patch.object(run, 'snapshot_jars', return_value=self.tmp), \
                mock.patch.object(run, 'run_one', side_effect=one):
            with self.assertRaises(SystemExit) as stop:
                quiet(run.main, ['--skip-build', '--seeds', '1337,42,7', '--batch', 'b1'])
            self.assertEqual(stop.exception.code, 1)
            runs = json.loads((self.tmp / 'build/soak-reports/b1/summary.json').read_text())['runs']
            self.assertEqual(runs['42-normal']['error'], 'RuntimeError: server.log missing')
            self.assertFalse(runs['7-normal']['completed'])
            self.assertTrue(runs['1337-normal']['completed'])
            self.assertIsNone(quiet(run.main, ['--skip-build', '--seeds', '1337', '--batch', 'b2'])[0])


class CompareTest(TempDir):
    def result(self, villagers, births, deaths, built, completed=True):
        return {'completed': completed, 'reason': 'completed' if completed else 'real_time_limit_run', 'start': {'villagers': 6},
                'end': {'villagers': villagers}, 'outcome': {'births': births, 'deaths': deaths, 'builtBlocks': built}}

    def write(self, batch, label, data):
        (self.tmp / batch / label).mkdir(parents=True, exist_ok=True)
        (self.tmp / batch / label / 'soak-result.json').write_text(json.dumps(data))

    def test_failed_and_missing_runs_stay_out_of_the_total(self):
        base, cand = compare.runs([FIXTURES / 'base']), compare.runs([FIXTURES / 'candidate'])
        self.assertEqual(cand['42-normal'][0]['reason'], 'runner_error')
        c = compare.compare(base, cand)
        self.assertEqual(c['labels'], ['1337-normal', '42-normal', '8675309-normal', '42-peaceful'])
        self.assertEqual(c['common'], ['1337-normal'])
        self.assertEqual(c['totals']['c'], {'villagers': 21, 'births': 4, 'deaths': 1, 'built': 40})
        self.assertEqual(c['totals']['b'], {'villagers': 20, 'births': 3, 'deaths': 2, 'built': 26})
        self.assertEqual(set(c['excluded']), {'42-normal', '8675309-normal', '42-peaceful'})
        self.assertEqual(c['excluded']['8675309-normal'], {'b': 'нет прогона'})
        self.assertIn('FileNotFoundError', c['excluded']['42-normal']['c'])
        self.assertIn('real_time_limit_run', c['excluded']['42-peaceful']['c'])

    def test_table_names_what_is_missing(self):
        _, text, _ = quiet(compare.main, ['--base', str(FIXTURES / 'base'), '--candidate', str(FIXTURES / 'candidate'),
                                          '--base-name', 'master', '--candidate-name', 'rc'])
        self.assertIn('| прогон | rc | master |', text)
        self.assertIn('| 1337-normal | 19→21, рожд. 4, смертей 1, кроватей +2, блоков 40 | 19→20, рожд. 3, смертей 2, кроватей +1, блоков 26 |', text)
        self.assertIn('| 42-normal | не завершён: FileNotFoundError', text)
        self.assertIn('| 8675309-normal | 6→7, рожд. 1, смертей 0, блоков 134 | нет прогона |', text)
        self.assertIn('| **итого**, общих прогонов 1 из 4 | жителей 21, рожд. 4, смертей 1, блоков 40 | жителей 20, рожд. 3, смертей 2, блоков 26 |', text)
        self.assertIn('Не вошли в итог: 42-normal (rc: не завершён', text)
        self.assertIn('8675309-normal (master: нет прогона)', text)

    def test_repeats_average_only_completed_runs(self):
        self.write('b1', '42-normal', self.result(7, 1, 0, 100))
        self.write('b2', '42-normal', self.result(9, 3, 0, 200))
        self.write('c1', '42-normal', self.result(8, 2, 1, 120))
        self.write('c2', '42-normal', {'completed': False, 'reason': 'real_time_limit_run'})
        c = compare.compare(compare.runs([self.tmp / 'b1', self.tmp / 'b2']), compare.runs([self.tmp / 'c1', self.tmp / 'c2']))
        self.assertEqual(c['totals']['b'], {'villagers': 8, 'births': 2, 'deaths': 0, 'built': 150})
        self.assertEqual(c['totals']['c'], {'villagers': 8, 'births': 2, 'deaths': 1, 'built': 120})
        self.assertEqual(c['partial'], [('42-normal', 'c')])

    def test_completed_run_without_outcome_is_not_zero(self):
        self.write('b', '1-normal', self.result(7, 1, 0, 100))
        self.write('c', '1-normal', {'completed': True, 'reason': 'completed', 'summaryError': 'NullPointerException'})
        c = compare.compare(compare.runs([self.tmp / 'b']), compare.runs([self.tmp / 'c']))
        self.assertEqual(c['common'], [])
        self.assertIn('NullPointerException', c['excluded']['1-normal']['c'])

    def test_run_folder_without_result_and_unreadable_result(self):
        (self.tmp / 'c/1-normal').mkdir(parents=True)
        (self.tmp / 'c/1-normal/server-tail.log.gz').write_bytes(b'')
        (self.tmp / 'c/jars').mkdir()
        self.write('c', '2-normal', {})
        (self.tmp / 'c/2-normal/soak-result.json').write_text('{"completed": tr')
        cand = compare.runs([self.tmp / 'c'])
        self.assertEqual(sorted(cand), ['1-normal', '2-normal'])
        self.assertEqual(cand['1-normal'][0]['reason'], 'no_result_file')
        self.assertIn('unreadable', cand['2-normal'][0]['reason'])

    def test_nothing_comparable_exits_nonzero(self):
        self.write('b', '1-normal', self.result(7, 1, 0, 100))
        self.write('c', '2-normal', self.result(7, 1, 0, 100))
        with self.assertRaises(SystemExit) as stop:
            quiet(compare.main, ['--base', str(self.tmp / 'b'), '--candidate', str(self.tmp / 'c')])
        self.assertIn('nothing to compare', stop.exception.code)
        (self.tmp / 'empty').mkdir()
        for folder in (self.tmp / 'empty', self.tmp / 'absent'):
            with self.assertRaises(SystemExit):
                compare.runs([folder])


class DigestTest(TempDir):
    def test_report_with_partial_samples(self):
        rows = digest.load(FIXTURES / 'report')
        self.assertEqual(len(rows), 3)
        lines = digest.digest(rows, workers=True)
        self.assertTrue(lines[0].lstrip().startswith('day'))
        self.assertIn('2×строит', '\n'.join(lines))
        self.assertTrue(any(l.split()[:3] == ['0.1', '6', '0'] and ' - ' in l for l in lines))
        self.assertTrue(any('Пётр' in l and l.rstrip().endswith('-') for l in lines))
        nav = next(l for l in lines if l.startswith('\nnavigation'))
        self.assertIn('snapshots 83.3', nav)
        self.assertIn('stage: growing | growth: beds', '\n'.join(lines))

    def test_main_prints_every_nth(self):
        _, out, _ = quiet(digest.main, [str(FIXTURES / 'report' / 'soak-timeline.jsonl'), '--every', '0'])
        self.assertIn('job failures since start', out)

    def test_cut_last_line_is_skipped_with_a_warning(self):
        timeline = self.tmp / 'soak-timeline.jsonl'
        timeline.write_text((FIXTURES / 'report/soak-timeline.jsonl').read_text(encoding='utf-8') + '{"tick": 48', encoding='utf-8')
        rows, _, err = quiet(digest.load, self.tmp)
        self.assertEqual(len(rows), 3)
        self.assertIn('1 unreadable line', err)

    def test_empty_timeline_fails_clearly(self):
        (self.tmp / 'soak-timeline.jsonl').write_text('\n')
        (self.tmp / 'soak-result.json').write_text(json.dumps({'completed': False, 'reason': 'no_villagers_loaded'}))
        with self.assertRaisesRegex(SystemExit, 'no samples.*no_villagers_loaded'):
            digest.load(self.tmp)

    def test_folder_without_timeline_or_result(self):
        with self.assertRaisesRegex(SystemExit, 'no timeline.*failed before the probe started'):
            digest.load(self.tmp)
        with self.assertRaisesRegex(SystemExit, 'no such report folder'):
            digest.load(self.tmp / 'absent')
        shutil.copytree(FIXTURES / 'report', self.tmp / '42-normal')
        with self.assertRaisesRegex(SystemExit, 'batch folder; pass one run: 42-normal'):
            digest.load(self.tmp)


class MapTest(TempDir):
    def test_frames_load_and_changes_follow_block_names(self):
        start, end = soakmap.load(FIXTURES / 'report/map-start.json'), soakmap.load(FIXTURES / 'report/map-end.json')
        self.assertEqual(start[1], [0, 0, 1, 0, 2, 0])
        self.assertEqual(soakmap.changed_columns(start, end), {1, 4, 5})
        self.assertEqual(soakmap.changed_columns(start, start), set())

    def test_changes_between_frames_of_different_areas(self):
        start = soakmap.load(FIXTURES / 'report/map-start.json')
        data = dict(start[0], minX=101, width=2, palette=['grass_block', 'oak_log'])
        self.assertEqual(soakmap.changed_columns(start, (data, [0, 1, 0, 1], [64] * 4)), {1, 2, 3})

    def test_frame_order_and_labels(self):
        found = soakmap.frames(FIXTURES / 'report')
        self.assertEqual([f.name for f in found], ['map-start.json', 'map-t012000.json', 'map-end.json'])
        self.assertEqual([soakmap.frame_label(f) for f in found], ['start', 'day 0.5', 'end'])
        self.assertEqual(soakmap.frames(self.tmp), [])

    def test_bad_frames_fail_clearly(self):
        good = json.loads((FIXTURES / 'report/map-start.json').read_text())
        cases = {'missing field(s) cells': {k: v for k, v in good.items() if k != 'cells'},
                 '5 cells and 6 heights': dict(good, cells='0,0,0,0,0,'),
                 'outside the palette': dict(good, cells='0,0,0,0,0,7,'),
                 'not a comma list': dict(good, heights='a,b,'),
                 'bad size': dict(good, width=0)}
        for message, data in cases.items():
            (self.tmp / 'map-start.json').write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, re.escape(message)):
                soakmap.load(self.tmp / 'map-start.json')
        (self.tmp / 'map-start.json').write_text('{"minX": 1,')
        with self.assertRaisesRegex(ValueError, 'not valid JSON'):
            soakmap.load(self.tmp / 'map-start.json')

    def test_report_without_frames(self):
        (self.tmp / 'soak-result.json').write_text(json.dumps({'completed': False, 'reason': 'no_village_found'}))
        with self.assertRaisesRegex(ValueError, 'no_village_found'):
            soakmap.load(self.tmp / 'map-end.json')
        with self.assertRaisesRegex(ValueError, 'no map frames'):
            soakmap.timelapse(self.tmp, 2, None)
        if soakmap.Image is not None:
            with self.assertRaises(SystemExit) as stop:
                soakmap.main([str(self.tmp)])
            self.assertIn('no_village_found', stop.exception.code)

    def test_without_pillow_main_says_so(self):
        with mock.patch.object(soakmap, 'Image', None), self.assertRaisesRegex(SystemExit, 'Pillow'):
            soakmap.main([str(FIXTURES / 'report')])

    @unittest.skipIf(soakmap.Image is None, 'Pillow is not installed')
    def test_png_and_gif(self):
        _, out, _ = quiet(soakmap.main, [str(FIXTURES / 'report'), '--scale', '2', '--out', str(self.tmp / 'map.png')])
        self.assertIn('3 columns changed', out)
        with soakmap.Image.open(self.tmp / 'map.png') as img:
            self.assertEqual(img.size, (3 * 2 * 2 + 8, 2 * 2))
        _, out, _ = quiet(soakmap.main, [str(FIXTURES / 'report'), '--gif', '--out', str(self.tmp / 'map.gif')])
        self.assertIn('3 frames', out)
        with soakmap.Image.open(self.tmp / 'map.gif') as img:
            self.assertEqual(img.n_frames, 3)


if __name__ == '__main__':
    unittest.main()
