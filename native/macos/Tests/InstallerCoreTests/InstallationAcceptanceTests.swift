import Darwin
import Foundation
import XCTest

@testable import InstallerCore

/// Runs the real journal, lock, replacement and recovery logic with deterministic process/trust inputs.
final class InstallationAcceptanceTests: XCTestCase {
    func testRejectedHandoffDoesNotAcknowledgeOrReplace() throws {
        try withFixture { fixture in
            let installer = fixture.installer(fault: "before-accept")
            XCTAssertThrowsError(try installer.install(directory: fixture.directory))
            XCTAssertFalse(fixture.exists("accepted"))
            try fixture.assertVersions(installed: "1", retained: "2")
            try installer.discard(directory: fixture.directory)
            XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.directory.path))
        }
    }

    func testExitVetoRetainsOriginalAndAllowsExplicitDiscard() throws {
        try withFixture { fixture in
            let installer = fixture.installer(keepHostAlive: true)
            XCTAssertThrowsError(try installer.install(directory: fixture.directory)) { error in
                XCTAssertTrue(String(describing: error).contains("Host did not exit"))
            }
            XCTAssertTrue(fixture.exists("accepted"))
            XCTAssertEqual(try installer.status(directory: fixture.directory), "original-retained")
            try fixture.assertVersions(installed: "1", retained: "2")
            XCTAssertThrowsError(try installer.confirm(directory: fixture.directory))
            try installer.discard(directory: fixture.directory)
        }
    }

    func testFailureBeforeSwapLeavesBothBundlesComplete() throws {
        try withFixture { fixture in
            let installer = fixture.installer(fault: "before-swap")
            XCTAssertThrowsError(try installer.install(directory: fixture.directory))
            XCTAssertEqual(try fixture.text("phase"), "replacing")
            XCTAssertEqual(try installer.status(directory: fixture.directory), "original-retained")
            try fixture.assertVersions(installed: "1", retained: "2")
        }
    }

    func testFailureAfterSwapRecoversFromStaleJournal() throws {
        try withFixture { fixture in
            let installer = fixture.installer(fault: "after-swap")
            XCTAssertThrowsError(try installer.install(directory: fixture.directory))
            XCTAssertEqual(try fixture.text("phase"), "replacing")
            XCTAssertEqual(try installer.status(directory: fixture.directory), "installed")
            try fixture.assertVersions(installed: "2", retained: "1")
            XCTAssertThrowsError(try installer.discard(directory: fixture.directory))
            try installer.confirm(directory: fixture.directory)
            XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.directory.path))
        }
    }

    func testRelaunchFailureRetainsOldBundleUntilConfirmation() throws {
        try withFixture { fixture in
            let installer = fixture.installer(fault: "before-relaunch")
            XCTAssertThrowsError(try installer.install(directory: fixture.directory))
            XCTAssertEqual(try fixture.text("phase"), "installed")
            try fixture.assertVersions(installed: "2", retained: "1")
            XCTAssertEqual(try installer.status(directory: fixture.directory), "installed")
        }
    }

    func testReusedHostPidCannotAuthorizeHandoff() throws {
        try withFixture { fixture in
            let installer = Installer(
                trust: FixtureCodeTrust(),
                identity: { _ in ProcessIdentity(seconds: 99, microseconds: 0) })
            XCTAssertThrowsError(try installer.install(directory: fixture.directory))
            XCTAssertFalse(fixture.exists("accepted"))
            try fixture.assertVersions(installed: "1", retained: "2")
        }
    }

    func testAcceptedTransactionCannotRunTwice() throws {
        try withFixture { fixture in
            let installer = fixture.installer(fault: "accepted")
            XCTAssertThrowsError(try installer.install(directory: fixture.directory))
            XCTAssertThrowsError(try installer.install(directory: fixture.directory)) { error in
                XCTAssertTrue(String(describing: error).contains("already handed off"))
            }
            try fixture.assertVersions(installed: "1", retained: "2")
        }
    }

    func testBundleChangedWhileWaitingCannotBeInstalled() throws {
        try withFixture { fixture in
            let installer = fixture.installer { phase in
                if phase == "accepted" { try fixture.writeApp(fixture.next, sequence: "3") }
            }
            XCTAssertThrowsError(try installer.install(directory: fixture.directory))
            try fixture.assertVersions(installed: "1", retained: "3")
        }
    }

    func testUnexpectedInstalledVersionPreservesRecoveryFiles() throws {
        try withFixture { fixture in
            try fixture.writeApp(fixture.target, sequence: "99")
            let installer = fixture.installer()
            XCTAssertThrowsError(try installer.status(directory: fixture.directory))
            XCTAssertThrowsError(try installer.confirm(directory: fixture.directory))
            XCTAssertThrowsError(try installer.discard(directory: fixture.directory))
            XCTAssertTrue(fixture.exists("record.json"))
        }
    }

    private func withFixture(_ body: (TransactionFixture) throws -> Void) throws {
        let fixture = try TransactionFixture()
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        try body(fixture)
    }
}

private struct FixtureCodeTrust: CodeTrust {
    func validateScope(_ request: InstallRequest) throws {}
    func validate(_ path: URL, teamId: String) throws {}
    func assess(_ application: URL) throws {}
}

private struct TransactionFixture {
    let root: URL
    let target: URL
    let directory: URL
    let next: URL
    let identity = ProcessIdentity(seconds: 1, microseconds: 2)

    init() throws {
        root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent(UUID().uuidString)
        target = root.appendingPathComponent("Fixture.app")
        let id = UUID().uuidString
        directory = root.appendingPathComponent(".kmp-update-saien.updater.fixture-\(id)")
        next = directory.appendingPathComponent("next.app")
        try writeApp(target, sequence: "1")
        try writeApp(next, sequence: "2")
        let request = InstallRequest(
            transactionId: id, target: target.path,
            archive: root.appendingPathComponent("archive.dmg").path, size: 1,
            sha256: String(repeating: "0", count: 64), appId: "saien.updater.fixture",
            teamId: "FIXTURE000", installedSequence: 1, releaseSequence: 2, hostPid: 123)
        let record = InstallRecord(
            request: request, transactionId: id, hostStartSeconds: identity.seconds,
            hostStartMicroseconds: identity.microseconds)
        try JSONEncoder().encode(record).write(to: directory.appendingPathComponent("record.json"))
    }

    func installer(
        fault: String = "", keepHostAlive: Bool = false,
        observe: @escaping (String) throws -> Void = { _ in }
    ) -> Installer {
        var calls = 0
        return Installer(
            trust: FixtureCodeTrust(), exitTimeout: 0.15,
            observe: { phase in
                try observe(phase)
                if phase == fault { throw InstallerFailure("Injected failure: \(phase)") }
            },
            identity: { _ in
                calls += 1
                return calls == 1 || keepHostAlive ? identity : nil
            })
    }

    func writeApp(_ path: URL, sequence: String) throws {
        let contents = path.appendingPathComponent("Contents")
        try FileManager.default.createDirectory(at: contents, withIntermediateDirectories: true)
        let info = ["CFBundleIdentifier": "saien.updater.fixture", "KMPUpdaterRelease": sequence]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0).write(
            to: contents.appendingPathComponent("Info.plist"))
        try Data(sequence.utf8).write(to: path.appendingPathComponent("complete-version"))
    }

    func assertVersions(installed: String, retained: String) throws {
        XCTAssertEqual(
            try String(
                contentsOf: target.appendingPathComponent("complete-version"), encoding: .utf8),
            installed)
        XCTAssertEqual(
            try String(
                contentsOf: next.appendingPathComponent("complete-version"), encoding: .utf8),
            retained)
    }

    func exists(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: directory.appendingPathComponent(name).path)
    }
    func text(_ name: String) throws -> String {
        try String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
    }
}
