#!/usr/bin/env python3
"""Controller boundary tests only: no Docker/SQL/NCP bootstrap completion proof."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('ncp_user_bootstrap', Path(__file__).with_name('ncp-user-bootstrap.py'))
bootstrap = importlib.util.module_from_spec(spec); spec.loader.exec_module(bootstrap)


class ControllerBoundaryTest(unittest.TestCase):
    def controller(self):
        request = {'postgres': {'container_id': 'c' * 64, 'image_id': 'sha256:' + 'd' * 64,
                                'network': 'map-prod-user-migration'},
                   'user': {'image_id': 'sha256:' + 'e' * 64,
                            'image': 'ghcr.io/we-meet-trip/map-service-user@sha256:' + 'e' * 64}}
        secrets = {'USER_BOOTSTRAP_MARKER': 'a' * 64, 'USER_BOOTSTRAP_PASSWORD': 'b' * 64,
                   'USER_DATABASE_PASSWORD': 'c' * 64, 'USER_MIGRATION_PASSWORD': 'd' * 64}
        return bootstrap.Controller(Mock(), Mock(), request, secrets)

    def test_gcp_database_is_rejected_before_receiver_import_or_commands(self):
        with patch.object(bootstrap.os, 'geteuid', return_value=0), \
             patch.object(bootstrap.sys, 'platform', 'linux'), \
             patch.object(bootstrap, 'read_json', return_value={'environment': 'test', 'project': 'map-test', 'database': 'map_test'}), \
             patch.object(bootstrap, 'load_receiver') as load, patch.object(bootstrap, 'command') as command:
            with self.assertRaisesRegex(bootstrap.BootstrapError, 'ncp_production_only'):
                bootstrap.run(Path('/opt/map-service-infra'))
            load.assert_not_called(); command.assert_not_called()

    def test_nonroot_rejected_without_reading_private_inputs(self):
        with patch.object(bootstrap.os, 'geteuid', return_value=501), patch.object(bootstrap, 'read_json') as read:
            with self.assertRaisesRegex(bootstrap.BootstrapError, 'ncp_root_linux_required'):
                bootstrap.run(Path('/opt/map-service-infra'))
            read.assert_not_called()

    def test_unreviewed_infra_path_rejected_before_private_reads(self):
        with patch.object(bootstrap, 'read_json') as read:
            with self.assertRaisesRegex(bootstrap.BootstrapError, 'reviewed_infra_path_required'):
                bootstrap.load_receiver(Path('/tmp/unreviewed'), {})
            read.assert_not_called()

    def test_standalone_checkout_rejects_worktree_without_git_execution(self):
        with patch.object(bootstrap, 'command') as command:
            with self.assertRaisesRegex(bootstrap.BootstrapError, 'standalone_checkout_required'):
                bootstrap.checkout(Path('/tmp/worktree'), 'a' * 40)
            command.assert_not_called()

    def test_bootstrap_main_has_inner_deadline_strict_environment_and_no_secret_arguments(self):
        args = bootstrap.bootstrap_command()
        self.assertEqual(args[:5], ['--signal=TERM', '--kill-after=5s', '120s', '/usr/bin/env', '-i'])
        shell = args[-1]
        self.assertIn('IFS= read -r USER_BOOTSTRAP_PASSWORD', shell)
        self.assertIn('IFS= read -r USER_BOOTSTRAP_MARKER', shell)
        self.assertIn('unset PWD OLDPWD SHLVL _', shell)
        self.assertIn('map.bootstrap.UserBootstrapApplication', shell)
        self.assertNotIn('map.migration.UserMigrationApplication', shell)
        for secret in self.controller().secrets.values():
            self.assertNotIn(secret, str(args))

    def test_real_shell_transfers_private_stdin_without_adding_forbidden_environment(self):
        args = bootstrap.bootstrap_command()[3:]
        args[-1] = args[-1].split('java -Xmx192m ', 1)[0] + '/usr/bin/env'
        result = bootstrap.subprocess.run(args, input='a' * 64 + '\n' + 'b' * 64 + '\n',
                                          capture_output=True, text=True, timeout=5,
                                          env={'PATH': '/usr/bin:/bin', 'SPRING_PROFILES_ACTIVE': 'forbidden'})
        self.assertEqual(result.returncode, 0)
        actual = dict(line.split('=', 1) for line in result.stdout.splitlines())
        self.assertEqual(set(actual), {'PATH', 'USER_BOOTSTRAP_PASSWORD', 'USER_BOOTSTRAP_MARKER',
                                      'USER_BOOTSTRAP_URL', 'USER_BOOTSTRAP_USERNAME',
                                      'USER_BOOTSTRAP_EXPECTED_DATABASE'})
        self.assertEqual(actual['USER_BOOTSTRAP_PASSWORD'], 'a' * 64)
        self.assertEqual(actual['USER_BOOTSTRAP_MARKER'], 'b' * 64)

    def test_operator_secrets_are_stdin_only_and_target_is_fixed(self):
        controller = self.controller()
        with patch.object(bootstrap, 'command', return_value='') as command:
            controller.operator('user-database-bootstrap-prepare.sql')
        args, kwargs = command.call_args
        self.assertEqual(args[0][:4], ['docker', 'exec', '-i', 'c' * 64])
        self.assertIn('map_prod', args[0])
        for secret in controller.secrets.values():
            self.assertIn(secret, kwargs['payload'])
            self.assertNotIn(secret, str(args[0][4:]))

    def test_runtime_authentication_uses_tcp_and_private_stdin(self):
        controller = self.controller()
        with patch.object(bootstrap, 'command', return_value='t') as command:
            self.assertEqual(controller.sql('SELECT true;', role='map_user_runtime'), 't')
        args, kwargs = command.call_args
        self.assertIn('-h postgres -U map_user_runtime -d map_prod', args[0][-1])
        self.assertEqual(kwargs['payload'], controller.secrets['USER_DATABASE_PASSWORD'] + '\nSELECT true;')
        self.assertNotIn(controller.secrets['USER_DATABASE_PASSWORD'], str(args[0][4:]))

    def test_failed_or_uncertain_prepare_never_quarantines_an_existing_role(self):
        controller = self.controller()
        controller.operator = Mock(side_effect=bootstrap.BootstrapError('bootstrap_command_failed'))
        with self.assertRaises(bootstrap.BootstrapError):
            controller.execute()
        self.assertFalse(controller.prepared)
        controller.operator.reset_mock()
        self.assertEqual(controller.quarantine(), 'manual_required')
        controller.operator.assert_not_called(); controller.backend.postgres.assert_not_called()

    def test_unverified_scram_host_rules_prevent_runtime_login_activation(self):
        controller = self.controller()
        controller.operator = Mock(); controller.launch = Mock(); controller.postgres = Mock()
        controller.sql = Mock(return_value='f')
        with self.assertRaisesRegex(bootstrap.BootstrapError, 'nonloopback_scram_authentication_required'):
            controller.execute()
        self.assertEqual([call.args[0] for call in controller.operator.call_args_list],
                         ['user-database-bootstrap-prepare.sql', 'user-database-bootstrap-finalize.sql'])
        controller.sql.assert_called_once_with(bootstrap.SCRAM_HOST_GUARD)

    def test_drifted_job_is_never_stopped_or_removed(self):
        controller = self.controller(); controller.job_id = 'f' * 64
        controller.backend.docker.return_value = '{"id":"wrong"}'
        with self.assertRaises(bootstrap.BootstrapError):
            controller.stop_job()
        self.assertEqual(controller.backend.docker.call_count, 1)
        self.assertEqual(controller.backend.docker.call_args.args[0][0], 'inspect')

    def test_quarantine_failure_stays_manual_and_does_not_reset_or_retry(self):
        controller = self.controller(); controller.prepared = True
        controller.backend.postgres.side_effect = RuntimeError('private operator diagnostic')
        controller.operator = Mock()
        self.assertEqual(controller.quarantine(), 'manual_required')
        controller.operator.assert_not_called()

    def test_quarantine_terminates_only_inventoried_pid_and_start_time(self):
        controller = self.controller(); controller.prepared = True
        controller.operator = Mock()
        controller.sql = Mock(side_effect=['[{"pid":42,"started_us":1788955200000000}]', 't', 't'])
        self.assertEqual(controller.quarantine(), 'confirmed')
        inventory, terminate, zero = [call.args[0] for call in controller.sql.call_args_list]
        self.assertIn("'map-user-bootstrap','map-user-privilege-check'", inventory)
        self.assertIn('AND pid=42 AND (extract(epoch FROM backend_start)*1000000)::bigint=1788955200000000', terminate)
        self.assertIn("datname=current_database() AND usename='map_user_bootstrap'", terminate)
        self.assertIn("NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE usename='map_user_bootstrap')", zero)

    def test_invalid_session_inventory_never_reaches_termination(self):
        controller = self.controller(); controller.prepared = True
        controller.operator = Mock()
        controller.sql = Mock(return_value='[{"pid":"42; DROP DATABASE existing","started_us":1}]')
        self.assertEqual(controller.quarantine(), 'manual_required')
        self.assertEqual(controller.sql.call_count, 1)

    def test_subprocess_failure_does_not_expose_output(self):
        child = Mock(returncode=1, pid=123)
        child.communicate.return_value = ('private-marker', 'private-password')
        with patch.object(bootstrap.subprocess, 'Popen', return_value=child):
            with self.assertRaisesRegex(bootstrap.BootstrapError, '^bootstrap_command_failed$'):
                bootstrap.command(['synthetic-command'])

    def test_job_json_success_cannot_hide_oom_nonzero_exit_or_running_process(self):
        for final in ({'running': False, 'exit_code': 0, 'oom_killed': True},
                      {'running': False, 'exit_code': 1, 'oom_killed': False},
                      {'running': True, 'exit_code': 0, 'oom_killed': False}):
            with self.subTest(final=final):
                controller = self.controller()
                controller.backend.docker.return_value = 'f' * 64
                controller.job_state = Mock(side_effect=[{}, final])
                controller.stop_job = Mock()
                output = json.dumps({'status': 'bootstrap_complete', 'migrations_executed': 4,
                                     'operator_finalization_required': True})
                with patch.object(bootstrap, 'command', return_value=output):
                    with self.assertRaisesRegex(bootstrap.BootstrapError, 'bootstrap_job_completion_not_confirmed'):
                        controller.launch()
                controller.stop_job.assert_not_called()

    def test_timeout_stops_only_own_child_process_group_and_suppresses_output(self):
        child = Mock(returncode=-15, pid=123)
        child.communicate.side_effect = [bootstrap.subprocess.TimeoutExpired('private command', 1),
                                         ('private marker', 'private password')]
        with patch.object(bootstrap.subprocess, 'Popen', return_value=child), \
             patch.object(bootstrap.os, 'killpg') as kill:
            with self.assertRaisesRegex(bootstrap.BootstrapError, '^bootstrap_command_unavailable$'):
                bootstrap.command(['synthetic-command'])
        kill.assert_called_once_with(123, bootstrap.signal.SIGTERM)

    def test_public_failure_contains_only_fixed_code_and_hold(self):
        output = io.StringIO()
        with patch.object(bootstrap, 'run', side_effect=RuntimeError('private marker and SQL')), \
             patch.object(bootstrap.signal, 'signal'), patch.object(bootstrap.sys, 'stdout', output):
            self.assertEqual(bootstrap.main(['--infra-root', '/opt/map-service-infra']), 1)
        self.assertEqual(json.loads(output.getvalue()), {'status': 'HOLD', 'public_serving': 'HOLD',
                         'automatic_retry_permitted': False, 'error_code': 'bootstrap_guard_failed'})


if __name__ == '__main__':
    unittest.main()
