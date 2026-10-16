"""Safety/retention tests use temporary dummy files, never Minecraft."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import test_artifacts as artifacts


class ArtifactTests(unittest.TestCase):
    def test_observation_trace_retains_decisions_without_repeating_full_blueprints(self):
        snapshot={'ticks':70000,'status':'night','state':{'buildings':[{'blocks':['unchanged']*1000}],'expedition':{'phase':'return','returnTrails':{'a':[1]*256}}},'actors':[{'uuid':'a','health':20,'job':'home','bag':['17 diamonds'],'nearBlocks':{'many':'blocks'}}],'evidence':{'currentStock':{'iron':5},'sharedChestContents':[1]*100}}
        result=artifacts.compact_observation(snapshot)
        self.assertEqual(result['actors'][0]['bag'],['17 diamonds'])
        self.assertEqual(result['expedition'],{'phase':'return'})
        self.assertEqual(result['evidence']['currentStock'],{'iron':5})
        self.assertNotIn('state',result)
        self.assertNotIn('nearBlocks',result['actors'][0])
    def test_compact_closed_timeline_survives_disposable_world_cleanup(self):
        import gzip
        lab=self.lab()
        data=gzip.compress(b'{"ticks":200}\n{"ticks":400}\n')
        (lab/'timeline.jsonl.gz').write_bytes(data)
        artifacts.cleanup_labs(self.root)
        report=self.root/'build/validation-reports/client-20261015-100000'
        self.assertFalse(lab.exists())
        self.assertEqual(gzip.decompress((report/'timeline.jsonl.gz').read_bytes()),b'{"ticks":200}\n{"ticks":400}\n')
    def test_both_observation_checkpoints_survive_world_cleanup_independently(self):
        lab = self.lab()
        for name,ticks in [('colony-checkpoint.json',48000),('colony-checkpoint-96000.json',96000)]:
            (lab / 'profile' / name).write_text(json.dumps({'ticks':ticks,'complete':False}),encoding='utf8')
        artifacts.cleanup_labs(self.root)
        report=self.root / 'build/validation-reports/client-20261015-100000'
        self.assertFalse(lab.exists())
        self.assertEqual(json.loads((report / 'colony-checkpoint.json').read_text())['ticks'],48000)
        self.assertEqual(json.loads((report / 'colony-checkpoint-96000.json').read_text())['ticks'],96000)

    def test_running_lab_keeps_only_six_generated_colony_frames(self):
        run = artifacts.ValidationRun(self.root, 'client')
        frames = run.output / 'profile/screenshots'
        frames.mkdir(parents=True)
        for index in range(10):
            (frames / f'colony-{index:05d}.png').write_bytes(b'png')
        (frames / 'unrelated.png').write_bytes(b'original screenshot')
        run.prune_screenshots()
        self.assertEqual([p.name for p in sorted(frames.glob('colony-*.png'))], [f'colony-{i:05d}.png' for i in range(4, 10)])
        self.assertEqual((frames / 'unrelated.png').read_bytes(), b'original screenshot')
        run.finish()

    def test_startup_errors_survive_log_tail_truncation(self):
        lab = self.lab()
        (lab / 'console.txt').write_text('[00:00:01] [main/ERROR]: startup problem\n' + 'normal output\n' * 25000, encoding='utf8')
        artifacts.cleanup_labs(self.root)
        report = self.root / 'build/validation-reports/client-20261015-100000'
        issues = json.loads((report / 'log-issues.json').read_text(encoding='utf8'))
        self.assertEqual(issues['groups'][0]['count'], 1)
        self.assertIn('startup problem', issues['groups'][0]['message'])
        import gzip
        self.assertNotIn(b'startup problem', gzip.decompress((report / 'console-tail.txt.gz').read_bytes()))

    def test_transient_windows_file_lock_retries_same_checked_lab(self):
        lab = self.lab()
        locked = PermissionError('temporary sharing violation')
        locked.winerror = 32
        remove = artifacts.shutil.rmtree
        with patch.object(artifacts.shutil, 'rmtree', side_effect=[locked, None]) as call, \
                patch.object(artifacts.time, 'sleep') as wait:
            artifacts._remove(self.root, lab, 'client-validation', artifacts.RUN_NAME)
        self.assertEqual(call.call_count, 2)
        self.assertTrue(all(args.args[0] == lab for args in call.call_args_list))
        wait.assert_called_once_with(.25)
        remove(lab)

    def test_persistent_lock_is_bounded_and_unrelated_errors_are_not_retried(self):
        lab = self.lab()
        locked = PermissionError('sharing violation')
        locked.winerror = 32
        with patch.object(artifacts.shutil, 'rmtree', side_effect=locked) as call, \
                patch.object(artifacts.time, 'sleep'):
            with self.assertRaises(PermissionError):
                artifacts._remove(self.root, lab, 'client-validation', artifacts.RUN_NAME)
            self.assertEqual(call.call_count, 12)
        with patch.object(artifacts.shutil, 'rmtree', side_effect=PermissionError('not a sharing lock')) as call, \
                patch.object(artifacts.time, 'sleep') as wait:
            with self.assertRaises(PermissionError):
                artifacts._remove(self.root, lab, 'client-validation', artifacts.RUN_NAME)
            call.assert_called_once()
            wait.assert_not_called()

    @unittest.skipUnless(os.name == 'nt', 'Actual Windows sharing-lock semantics')
    def test_actual_windows_open_file_releases_before_bounded_cleanup(self):
        import threading
        lab = self.lab()
        locked = lab / 'profile/mods/copied.jar'
        handle = locked.open('rb')
        release = threading.Timer(.4, handle.close)
        release.start()
        try:
            artifacts._remove(self.root, lab, 'client-validation', artifacts.RUN_NAME)
            self.assertFalse(lab.exists())
        finally:
            release.join(timeout=2)
            handle.close()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.addCleanup(self.temp.cleanup)

    def lab(self, name='20261015-100000', kind='client'):
        lab = self.root / 'build' / artifacts.KINDS[kind] / name
        lab.mkdir(parents=True)
        (lab / 'summary.json').write_text('{"success": false}', encoding='utf8')
        (lab / 'console.txt').write_text('real failure reason\n', encoding='utf8')
        (lab / 'profile/mods').mkdir(parents=True)
        (lab / 'profile/mods/copied.jar').write_bytes(b'copied runtime')
        return lab

    def test_cleanup_preserves_evidence_and_unrelated_files(self):
        lab = self.lab()
        original = self.root / 'original-world/level.dat'
        original.parent.mkdir()
        original.write_bytes(b'valuable save')
        source = self.root / 'src/Important.java'
        source.parent.mkdir()
        source.write_text('source code')
        count, freed = artifacts.cleanup_labs(self.root)
        self.assertEqual(count, 1)
        self.assertGreater(freed, 0)
        self.assertFalse(lab.exists())
        report = self.root / 'build/validation-reports/client-20261015-100000'
        self.assertEqual(json.loads((report / 'summary.json').read_text()), {'success': False})
        import gzip
        self.assertIn(b'real failure reason', gzip.decompress((report / 'console-tail.txt.gz').read_bytes()))
        self.assertFalse(any(report.rglob('*.jar')))
        self.assertEqual(original.read_bytes(), b'valuable save')
        self.assertEqual(source.read_text(), 'source code')

    def test_outside_path_and_unexpected_names_rejected(self):
        outside = self.root / 'original-world'
        outside.mkdir()
        with self.assertRaises(ValueError):
            artifacts._remove(self.root, outside, 'client-validation', artifacts.RUN_NAME)
        odd = self.root / 'build/validation/KEEP-ME'
        odd.mkdir(parents=True)
        with self.assertRaises(ValueError):
            artifacts.cleanup_labs(self.root)
        self.assertTrue(odd.exists())

    def test_junction_cannot_redirect_cleanup(self):
        outside = self.root / 'original-world'
        outside.mkdir()
        (outside / 'level.dat').write_bytes(b'save')
        target = self.root / 'build/client-validation/20261015-100000'
        target.parent.mkdir(parents=True)
        if os.name == 'nt':
            # Native PowerShell command, no shell chaining or deletion via cmd.
            script = '$null = New-Item -ItemType Junction -Path $env:LAB_LINK -Target $env:LAB_TARGET'
            env = dict(os.environ, LAB_LINK=str(target), LAB_TARGET=str(outside))
            subprocess.run(['powershell', '-NoProfile', '-Command', script], env=env, check=True,
                           capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
        else:
            target.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            artifacts.cleanup_labs(self.root)
        self.assertEqual((outside / 'level.dat').read_bytes(), b'save')

    def test_disposable_world_source_rejected_before_pruning(self):
        lab = self.lab()
        with self.assertRaises(ValueError):
            artifacts.reject_disposable_source(self.root, lab / 'profile')
        self.assertTrue(lab.exists())

    def test_report_count_and_combined_budget(self):
        base = self.root / 'build/validation-reports'
        for index in range(5):
            report = base / f'client-20261015-10000{index}'
            report.mkdir(parents=True)
            (report / 'data').write_bytes(b'x' * 10)
        with patch.object(artifacts, 'REPORT_COUNT', 3), patch.object(artifacts, 'REPORT_LIMIT', 25):
            artifacts.trim_reports(self.root)
        self.assertEqual(sorted(p.name for p in base.iterdir()),
                         ['client-20261015-100003', 'client-20261015-100004'])

    def test_default_finish_discards_lab_and_keep_is_pruned_next_time(self):
        run = artifacts.ValidationRun(self.root, 'client')
        (run.output / 'summary.json').write_text('{"success": true}')
        run.finish()
        self.assertFalse(run.output.exists())
        kept = artifacts.ValidationRun(self.root, 'server', keep_lab=True)
        kept.finish()
        self.assertTrue(kept.output.exists())
        next_run = artifacts.ValidationRun(self.root, 'client')
        self.assertFalse(kept.output.exists())
        next_run.finish()

    def test_budget_failure_discards_even_explicitly_retained_lab(self):
        run = artifacts.ValidationRun(self.root, 'client', keep_lab=True)
        (run.output / 'payload').write_bytes(b'x' * 11)
        with patch.object(artifacts, 'LAB_LIMIT', 10):
            with self.assertRaisesRegex(RuntimeError, 'exceeds'):
                run.check_budget()
        run.finish()
        self.assertFalse(run.output.exists())

    def test_copy_rejected_before_large_payload_is_duplicated(self):
        run = artifacts.ValidationRun(self.root, 'client')
        source = self.root / 'large-world'
        source.mkdir()
        (source / 'region').write_bytes(b'x' * 11)
        with patch.object(artifacts, 'LAB_LIMIT', 10):
            with self.assertRaisesRegex(RuntimeError, 'Copied'):
                run.check_copy_budget([source])
        run.finish()
        self.assertEqual((source / 'region').stat().st_size, 11)

    def test_abandoned_runner_with_live_game_is_not_deleted(self):
        lab = self.lab()
        (lab / 'process-state.json').write_text(json.dumps({'active': True, 'pids': [os.getpid()]}))
        with self.assertRaisesRegex(RuntimeError, 'still running'):
            artifacts.cleanup_labs(self.root)
        self.assertTrue(lab.exists())

    def test_finish_refuses_to_delete_live_game(self):
        from unittest.mock import Mock
        run = artifacts.ValidationRun(self.root, 'client')
        process = Mock(pid=os.getpid())
        process.poll.return_value = None
        run.register_process(process)
        with self.assertRaisesRegex(RuntimeError, 'still running'):
            run.finish()
        self.assertTrue(run.output.exists())
        process.poll.return_value = 0
        run.finish()

    def test_runtime_budget_violation_requests_game_shutdown(self):
        run = artifacts.ValidationRun(self.root, 'client')
        (run.output / 'profile').mkdir()
        with patch.object(run._stop, 'wait', return_value=False), \
                patch.object(run, 'check_budget', side_effect=[None, RuntimeError('quota exceeded')]):
            run.start_monitor()
            run._monitor.join(timeout=3)
        self.assertFalse(run._monitor.is_alive())
        self.assertEqual(run.budget_error, 'quota exceeded')
        self.assertEqual((run.output / 'profile/stop-requested').read_text(), 'quota exceeded')
        run.finish()

    def test_setup_failure_atexit_cleans_without_game_launch(self):
        script = ('from pathlib import Path; from test_artifacts import ValidationRun; '
                  f'r=ValidationRun(Path({str(self.root)!r}), "client"); '
                  'raise RuntimeError("simulated copy failure")')
        result = subprocess.run([sys.executable, '-c', script], cwd=Path(__file__).parent,
                                capture_output=True, text=True,
                                **({'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any((self.root / 'build/client-validation').iterdir()))
        self.assertEqual(len(list((self.root / 'build/validation-reports').iterdir())), 1)


if __name__ == '__main__':
    unittest.main()
