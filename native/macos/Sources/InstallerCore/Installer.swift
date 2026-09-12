import CryptoKit
import Darwin
import Foundation
import Security

public struct InstallerFailure: Error, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

public struct InstallRequest: Codable {
    public let transactionId: String
    public let target: String
    public let archive: String
    public let size: Int64
    public let sha256: String
    public let appId: String
    public let teamId: String
    public let installedSequence: Int64
    public let releaseSequence: Int64
    public let hostPid: Int32
}

public struct InstallRecord: Codable {
    public let request: InstallRequest
    public let transactionId: String
    public let hostStartSeconds: UInt64
    public let hostStartMicroseconds: UInt64
}

public struct ProcessIdentity: Equatable {
    public let seconds: UInt64
    public let microseconds: UInt64
}

public struct Installer {
    private let trust: any CodeTrust
    private let exitTimeout: TimeInterval
    private let observe: (String) throws -> Void
    private let identity: (Int32) -> ProcessIdentity?

    public init(
        trust: any CodeTrust = DeveloperIDTrust(),
        exitTimeout: TimeInterval = 120,
        observe: @escaping (String) throws -> Void = { _ in },
        identity: @escaping (Int32) -> ProcessIdentity? = SystemProcessIdentity.read
    ) {
        precondition(exitTimeout > 0 && exitTimeout <= 120)
        self.trust = trust
        self.exitTimeout = exitTimeout
        self.observe = observe
        self.identity = identity
    }

    private var fm: FileManager { FileManager.default }

    public func prepare(requestFile: URL) throws -> URL {
        let request: InstallRequest = try read(requestFile)
        try trust.validateScope(request)
        let target = try canonicalDirectory(URL(fileURLWithPath: request.target))
        guard target.pathExtension == "app", request.releaseSequence > request.installedSequence,
            request.installedSequence >= 0, request.size > 0,
            request.appId.range(
                of: "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", options: .regularExpression) != nil,
            request.teamId.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil
        else { throw InstallerFailure("Invalid installation request") }
        guard request.target == target.path else {
            throw InstallerFailure("Target must be canonical")
        }
        guard fm.isWritableFile(atPath: target.deletingLastPathComponent().path) else {
            throw InstallerFailure("Installation requires a writable application directory")
        }
        try validateApp(target, request: request, sequence: request.installedSequence)
        try validateHelper(teamId: request.teamId)
        guard let identity = processIdentity(request.hostPid),
            processPath(request.hostPid)?.hasPrefix(target.path + "/") == true
        else {
            throw InstallerFailure("Host process does not belong to the installed application")
        }
        let archive = URL(fileURLWithPath: request.archive)
        try verifyArchive(archive, size: request.size, digest: request.sha256)
        guard UUID(uuidString: request.transactionId) != nil else {
            throw InstallerFailure("Invalid transaction identifier")
        }
        let transactionId = request.transactionId
        let directory = target.deletingLastPathComponent().appendingPathComponent(
            ".kmp-update-\(request.appId)-\(transactionId)")
        try fm.createDirectory(
            at: directory, withIntermediateDirectories: false,
            attributes: [.posixPermissions: 0o700])
        var prepared = false
        defer { if !prepared { try? fm.removeItem(at: directory) } }
        let mount = directory.appendingPathComponent("mount")
        try fm.createDirectory(at: mount, withIntermediateDirectories: false)
        var mounted = false
        defer {
            if mounted { _ = try? run("/usr/bin/hdiutil", ["detach", mount.path], timeout: 30) }
        }
        _ = try run(
            "/usr/bin/hdiutil",
            [
                "attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", mount.path,
                archive.path,
            ], timeout: 120)
        mounted = true
        let applications = try fm.contentsOfDirectory(
            at: mount, includingPropertiesForKeys: [.isSymbolicLinkKey]
        )
        .filter { $0.pathExtension == "app" }
        guard applications.count == 1 else {
            throw InstallerFailure("DMG must contain exactly one top-level application")
        }
        let source = try canonicalDirectory(applications[0])
        guard source.path.hasPrefix(mount.path + "/") else {
            throw InstallerFailure("Application escaped mounted image")
        }
        try validateApp(source, request: request, sequence: request.releaseSequence)
        try trust.assess(source)
        let next = directory.appendingPathComponent("next.app")
        _ = try run("/usr/bin/ditto", [source.path, next.path], timeout: 600)
        try validateApp(next, request: request, sequence: request.releaseSequence)
        _ = try run("/usr/bin/hdiutil", ["detach", mount.path], timeout: 30)
        mounted = false
        try fm.removeItem(at: mount)
        let record = InstallRecord(
            request: request, transactionId: transactionId, hostStartSeconds: identity.seconds,
            hostStartMicroseconds: identity.microseconds)
        try write(record, to: directory.appendingPathComponent("record.json"))
        prepared = true
        return directory
    }

    /// Acknowledges after validation; replacement happens only after the exact host exits.
    public func install(directory: URL) throws {
        let directory = try canonicalDirectory(directory)
        let record: InstallRecord = try read(directory.appendingPathComponent("record.json"))
        try trust.validateScope(record.request)
        try validateDirectory(directory, record: record)
        let target = URL(fileURLWithPath: record.request.target)
        let next = directory.appendingPathComponent("next.app")
        let lock = try destinationLock(target: target, appId: record.request.appId)
        defer {
            flock(lock, LOCK_UN)
            Darwin.close(lock)
        }
        guard !fm.fileExists(atPath: directory.appendingPathComponent("accepted").path) else {
            throw InstallerFailure(
                "Transaction was already handed off; inspect status before recovery")
        }
        try validateHelper(teamId: record.request.teamId)
        try validateApp(target, request: record.request, sequence: record.request.installedSequence)
        try validateApp(next, request: record.request, sequence: record.request.releaseSequence)
        let identity = ProcessIdentity(
            seconds: record.hostStartSeconds, microseconds: record.hostStartMicroseconds)
        guard processIdentity(record.request.hostPid) == identity else {
            throw InstallerFailure("Host identity changed before handoff")
        }
        try observe("before-accept")
        try durableWrite(
            Data(record.transactionId.utf8), to: directory.appendingPathComponent("accepted"))
        try observe("accepted")
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(exitTimeout))
        while processIdentity(record.request.hostPid) == identity {
            guard clock.now < deadline else {
                throw InstallerFailure("Host did not exit; original application retained")
            }
            Thread.sleep(forTimeInterval: 0.1)
        }
        // Repeat identity checks at the actual replacement boundary.
        try validateApp(target, request: record.request, sequence: record.request.installedSequence)
        try validateApp(next, request: record.request, sequence: record.request.releaseSequence)
        try durableWrite(Data("replacing".utf8), to: directory.appendingPathComponent("phase"))
        try observe("before-swap")
        try atomicSwap(target, next)
        try observe("after-swap")
        // next.app now holds the original app. Keep it for explicit recovery/cleanup.
        try durableWrite(Data("installed".utf8), to: directory.appendingPathComponent("phase"))
        try observe("before-relaunch")
        _ = try run("/usr/bin/open", [target.path], timeout: 30)
        try durableWrite(
            Data("relaunch-requested".utf8), to: directory.appendingPathComponent("phase"))
    }

    /// Infers replacement outcome from validated bundles, even if the journal write was interrupted.
    public func status(directory: URL) throws -> String {
        let directory = try canonicalDirectory(directory)
        let record: InstallRecord = try read(directory.appendingPathComponent("record.json"))
        try trust.validateScope(record.request)
        try validateDirectory(directory, record: record)
        let lock = try destinationLock(
            target: URL(fileURLWithPath: record.request.target), appId: record.request.appId)
        defer {
            flock(lock, LOCK_UN)
            Darwin.close(lock)
        }
        return try replacementStatus(record: record)
    }

    private func replacementStatus(record: InstallRecord) throws -> String {
        let target = URL(fileURLWithPath: record.request.target)
        let actual = try embeddedSequence(target)
        if actual == record.request.releaseSequence {
            try validateApp(target, request: record.request, sequence: actual)
            return "installed"
        }
        if actual == record.request.installedSequence {
            try validateApp(target, request: record.request, sequence: actual)
            return "original-retained"
        }
        throw InstallerFailure(
            "Unexpected application identity; preserve transaction files for recovery")
    }

    /// Discard only an inactive transaction whose original application is still installed.
    public func discard(directory: URL) throws {
        let directory = try canonicalDirectory(directory)
        let record: InstallRecord = try read(directory.appendingPathComponent("record.json"))
        try trust.validateScope(record.request)
        try validateDirectory(directory, record: record)
        let lock = try destinationLock(
            target: URL(fileURLWithPath: record.request.target), appId: record.request.appId)
        defer {
            flock(lock, LOCK_UN)
            Darwin.close(lock)
        }
        guard try replacementStatus(record: record) == "original-retained" else {
            throw InstallerFailure("Installed transactions retain the old app for recovery")
        }
        try fm.removeItem(at: directory)
    }

    /// The host calls this after a successful launch of the new release.
    public func confirm(directory: URL) throws {
        let directory = try canonicalDirectory(directory)
        let record: InstallRecord = try read(directory.appendingPathComponent("record.json"))
        try trust.validateScope(record.request)
        try validateDirectory(directory, record: record)
        let lock = try destinationLock(
            target: URL(fileURLWithPath: record.request.target), appId: record.request.appId)
        defer {
            flock(lock, LOCK_UN)
            Darwin.close(lock)
        }
        guard try replacementStatus(record: record) == "installed" else {
            throw InstallerFailure("New release is not installed")
        }
        try fm.removeItem(at: directory)
    }

    public func atomicSwap(_ target: URL, _ staged: URL) throws {
        let target = try canonicalDirectory(target)
        let staged = try canonicalDirectory(staged)
        guard target != staged, !target.path.hasPrefix(staged.path + "/"),
            !staged.path.hasPrefix(target.path + "/")
        else {
            throw InstallerFailure("Invalid replacement relationship")
        }
        guard renameatx_np(AT_FDCWD, target.path, AT_FDCWD, staged.path, UInt32(RENAME_SWAP)) == 0
        else {
            throw InstallerFailure("Atomic replacement failed (errno \(errno)); original retained")
        }
        let fd = Darwin.open(target.deletingLastPathComponent().path, O_RDONLY)
        guard fd >= 0 else {
            throw InstallerFailure("Cannot open replacement directory for synchronization")
        }
        defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else {
            throw InstallerFailure("Replacement completed but directory synchronization failed")
        }
    }

    public func processIdentity(_ pid: Int32) -> ProcessIdentity? { identity(pid) }

    public func verifyArchive(_ path: URL, size: Int64, digest: String) throws {
        let values = try path.resourceValues(forKeys: [
            .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey,
        ])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
            Int64(values.fileSize ?? -1) == size
        else {
            throw InstallerFailure("Archive length/type changed")
        }
        let input = try FileHandle(forReadingFrom: path)
        defer { try? input.close() }
        var hash = SHA256()
        var count: Int64 = 0
        while let chunk = try input.read(upToCount: 65536), !chunk.isEmpty {
            count += Int64(chunk.count)
            guard count <= size else { throw InstallerFailure("Archive grew after verification") }
            hash.update(data: chunk)
        }
        let actual = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard count == size, actual == digest else {
            throw InstallerFailure("Archive integrity check failed")
        }
    }

    private func validateApp(_ path: URL, request: InstallRequest, sequence: Int64) throws {
        _ = try canonicalDirectory(path)
        let info = try appInfo(path)
        guard info["CFBundleIdentifier"] as? String == request.appId,
            try embeddedSequence(path) == sequence,
            info["NSUpdateSecurityPolicy"] == nil
        else {
            throw InstallerFailure("Application identity, release or update policy does not match")
        }
        try trust.validate(path, teamId: request.teamId)
    }

    private func validateHelper(teamId: String) throws {
        let executable = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
        try trust.validate(executable, teamId: teamId)
    }

    private func appInfo(_ path: URL) throws -> [String: Any] {
        let data = try Data(contentsOf: path.appendingPathComponent("Contents/Info.plist"))
        guard data.count <= 262144,
            let info = try PropertyListSerialization.propertyList(from: data, format: nil)
                as? [String: Any]
        else { throw InstallerFailure("Invalid app metadata") }
        return info
    }

    private func embeddedSequence(_ path: URL) throws -> Int64 {
        let info = try appInfo(path)
        guard let text = info["KMPUpdaterRelease"] as? String, let sequence = Int64(text),
            sequence >= 0
        else {
            throw InstallerFailure("Application must embed KMPUpdaterRelease as a decimal string")
        }
        return sequence
    }

    private func validateDirectory(_ directory: URL, record: InstallRecord) throws {
        let target = URL(fileURLWithPath: record.request.target)
        let expected = target.deletingLastPathComponent().appendingPathComponent(
            ".kmp-update-\(record.request.appId)-\(record.transactionId)")
        guard directory == expected, UUID(uuidString: record.transactionId) != nil,
            record.request.appId.range(
                of: "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", options: .regularExpression) != nil,
            record.request.teamId.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil,
            record.request.releaseSequence > record.request.installedSequence
        else {
            throw InstallerFailure("Invalid transaction location or release order")
        }
    }

    private func canonicalDirectory(_ path: URL) throws -> URL {
        let values = try path.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard values.isDirectory == true, values.isSymbolicLink != true else {
            throw InstallerFailure("Expected a real directory")
        }
        let normalized = path.standardizedFileURL
        guard normalized == normalized.resolvingSymlinksInPath() else {
            throw InstallerFailure("Symlink in directory path")
        }
        return normalized
    }

    private func processPath(_ pid: Int32) -> String? {
        // PROC_PIDPATHINFO_MAXSIZE is a C expression macro, unavailable to Swift.
        var buffer = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        guard proc_pidpath(pid, &buffer, UInt32(buffer.count)) > 0 else { return nil }
        return String(
            decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }

    private func destinationLock(target: URL, appId: String) throws -> Int32 {
        let file = target.deletingLastPathComponent().appendingPathComponent(
            ".kmp-update-\(appId).lock")
        let fd = Darwin.open(file.path, O_CREAT | O_RDWR | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw InstallerFailure("Cannot open installation lock") }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else {
            Darwin.close(fd)
            throw InstallerFailure("Another installer owns this application")
        }
        return fd
    }

    private func read<T: Decodable>(_ path: URL) throws -> T {
        let values = try path.resourceValues(forKeys: [
            .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey,
        ])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
            (values.fileSize ?? Int.max) <= 16384
        else {
            throw InstallerFailure("Invalid installer record")
        }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: path))
    }

    private func write<T: Encodable>(_ value: T, to path: URL) throws {
        try durableWrite(JSONEncoder().encode(value), to: path)
    }

    private func durableWrite(_ data: Data, to path: URL) throws {
        try data.write(to: path, options: .atomic)
        let handle = try FileHandle(forWritingTo: path)
        defer { try? handle.close() }
        try handle.synchronize()
        let fd = Darwin.open(path.deletingLastPathComponent().path, O_RDONLY)
        guard fd >= 0 else { throw InstallerFailure("Cannot synchronize transaction directory") }
        defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else {
            throw InstallerFailure("Cannot synchronize transaction directory")
        }
    }

    private func run(_ executable: String, _ arguments: [String], timeout: TimeInterval) throws
        -> Int32
    {
        try SystemCommand.run(executable, arguments, timeout: timeout)
    }
}
