import CryptoKit
import Darwin
import Foundation
import XCTest

@testable import InstallerCore

final class InstallerTests: XCTestCase {
    func testAtomicSwapKeepsCompleteOldAndNewDirectories() throws {
        let root = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let old = root.appendingPathComponent("App.app")
        let new = root.appendingPathComponent("next.app")
        for directory in [old, new] {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: false)
        }
        try Data("old".utf8).write(to: old.appendingPathComponent("version"))
        try Data("new".utf8).write(to: new.appendingPathComponent("version"))
        try Installer().atomicSwap(old, new)
        XCTAssertEqual(
            try String(contentsOf: old.appendingPathComponent("version"), encoding: .utf8), "new")
        XCTAssertEqual(
            try String(contentsOf: new.appendingPathComponent("version"), encoding: .utf8), "old")
    }

    func testInvalidReplacementLeavesOriginalIntact() throws {
        let root = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let original = root.appendingPathComponent("version")
        try Data("old".utf8).write(to: original)
        XCTAssertThrowsError(try Installer().atomicSwap(root, root))
        XCTAssertThrowsError(
            try Installer().atomicSwap(root, root.appendingPathComponent("missing")))
        XCTAssertEqual(try String(contentsOf: original, encoding: .utf8), "old")
    }

    func testSymlinkReplacementIsRejected() throws {
        let root = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let link = root.appendingPathComponent("link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: root)
        XCTAssertThrowsError(try Installer().atomicSwap(root, link))
    }

    func testArchiveValidationUsesActualBytesAndLength() throws {
        let root = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let archive = root.appendingPathComponent("archive")
        let data = Data("test artifact".utf8)
        try data.write(to: archive)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        try Installer().verifyArchive(archive, size: Int64(data.count), digest: digest)
        XCTAssertThrowsError(
            try Installer().verifyArchive(archive, size: Int64(data.count + 1), digest: digest))
        XCTAssertThrowsError(
            try Installer().verifyArchive(
                archive, size: Int64(data.count), digest: String(repeating: "0", count: 64)))
    }

    func testProcessIdentityIncludesStartTimeAndRejectsInvalidPid() {
        XCTAssertNotNil(Installer().processIdentity(getpid()))
        XCTAssertEqual(Installer().processIdentity(getpid()), Installer().processIdentity(getpid()))
        XCTAssertNil(Installer().processIdentity(-1))
        XCTAssertNil(Installer().processIdentity(0))
    }

    private func temporaryDirectory() throws -> URL {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        return root
    }
}
