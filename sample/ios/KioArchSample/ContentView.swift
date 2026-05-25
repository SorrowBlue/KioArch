import SwiftUI
import UniformTypeIdentifiers
import KioArch

// Helper extensions for KMP type conversion in Swift
extension KotlinByteArray {
    func toData() -> Data {
        let size = self.size
        var data = Data(count: Int(size))
        for i in 0..<size {
            data[Int(i)] = UInt8(bitPattern: self.get(index: Int32(i)))
        }
        return data
    }
}

// Swift implementation of Kotlin's SeekableSource interface
class FileSeekableSource: NSObject, SeekableSource {
    private let fileHandle: FileHandle
    private let fileLength: Int64

    init?(path: String) {
        guard let handle = FileHandle(forReadingAtPath: path) else {
            return nil
        }
        self.fileHandle = handle
        
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: path)
            if let size = attributes[.size] as? UInt64 {
                self.fileLength = Int64(size)
            } else {
                self.fileLength = 0
            }
        } catch {
            return nil
        }
    }

    func read(buffer: KotlinByteArray, offset: Int32, length: Int32) -> Int32 {
        do {
            guard let data = try fileHandle.read(upToCount: Int(length)) else {
                return -1 // EOF
            }
            if data.isEmpty {
                return -1 // EOF
            }
            
            // Copy Swift Data into KotlinByteArray
            for (i, byte) in data.enumerated() {
                buffer.set(index: offset + Int32(i), value: Int8(bitPattern: byte))
            }
            return Int32(data.count)
        } catch {
            return -1
        }
    }

    func seek(position: Int64) {
        do {
            try fileHandle.seek(toOffset: UInt64(position))
        } catch {
            print("FileSeekableSource seek error: \(error)")
        }
    }

    func position() -> Int64 {
        do {
            let offset = try fileHandle.offset()
            return Int64(offset)
        } catch {
            return 0
        }
    }

    func length() -> Int64 {
        return fileLength
    }

    func close() {
        do {
            try fileHandle.close()
        } catch {
            print("FileSeekableSource close error: \(error)")
        }
    }
}

// Structure to represent a file/folder node in the explorer tree
struct FilerNode: Identifiable {
    let id: String // Unique ID (uses fullName)
    let name: String // Display name in the current directory level
    let fullName: String // Full path in archive
    let isDirectory: Bool
    let size: Int64
    let crc: Int64
    let entry: ArchiveEntry? // Original entry, only if it is a file
}

struct ContentView: View {
    @State private var fileName: String = ""
    @State private var entries: [ArchiveEntry] = []
    @State private var errorMessage: String? = nil
    @State private var isLoading = false
    @State private var isFileImporterPresented = false
    
    // Directory navigation states
    @State private var currentPath: String = "" // Empty string represent root "/"
    
    // Active reader reference to allow extraction later
    @State private var activeReader: ArchiveReader? = nil
    
    // Preview states
    @State private var selectedEntry: ArchiveEntry? = nil
    @State private var previewText: String? = nil
    @State private var previewImage: UIImage? = nil
    @State private var isExtracting = false

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header with custom gradient and premium look
                VStack(spacing: 12) {
                    Text("KioArch Explorer")
                        .font(.system(.title, design: .rounded))
                        .fontWeight(.heavy)
                        .foregroundColor(.primary)
                    
                    Text("Kotlin Multiplatform Archive Reader")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    
                    if !fileName.isEmpty {
                        HStack {
                            Image(systemName: "archivebox.fill")
                                .foregroundColor(.accentColor)
                            Text(fileName)
                                .font(.headline)
                                .foregroundColor(.primary)
                                .lineLimit(1)
                        }
                        .padding(.vertical, 8)
                        .padding(.horizontal, 16)
                        .background(Capsule().fill(Color(.tertiarySystemBackground)))
                    }
                    
                    Button(action: {
                        isFileImporterPresented = true
                    }) {
                        HStack {
                            Image(systemName: "doc.badge.plus")
                            Text(fileName.isEmpty ? "Select Archive File" : "Choose Another File")
                        }
                        .font(.system(size: 16, weight: .semibold))
                        .padding(.vertical, 10)
                        .padding(.horizontal, 24)
                        .foregroundColor(.white)
                        .background(LinearGradient(colors: [.blue, .purple], startPoint: .leading, endPoint: .trailing))
                        .cornerRadius(30)
                        .shadow(color: .blue.opacity(0.3), radius: 8, x: 0, y: 4)
                    }
                    .padding(.top, 4)
                }
                .padding(.vertical, 20)
                .frame(maxWidth: .infinity)
                .background(
                    LinearGradient(
                        colors: [Color(.systemBackground), Color(.secondarySystemBackground)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                
                // Active explorer area
                if isLoading {
                    Spacer()
                    ProgressView("Analyzing archive...")
                        .scaleEffect(1.2)
                        .padding()
                    Spacer()
                } else if let error = errorMessage {
                    Spacer()
                    VStack(spacing: 16) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 48))
                            .foregroundColor(.orange)
                        Text(error)
                            .font(.headline)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                        Button("Select File") {
                            isFileImporterPresented = true
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    Spacer()
                } else if entries.isEmpty {
                    Spacer()
                    VStack(spacing: 16) {
                        Image(systemName: "doc.zipper")
                            .font(.system(size: 64))
                            .foregroundColor(.secondary.opacity(0.6))
                        Text("No archive loaded.")
                            .font(.headline)
                            .foregroundColor(.secondary)
                        Text("Supports ZIP, 7z, TAR, GZIP formats")
                            .font(.subheadline)
                            .foregroundColor(.secondary.opacity(0.8))
                    }
                    Spacer()
                } else {
                    // Breadcrumb navigation bar (Horizontal scrolling folder hierarchy)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            Button(action: {
                                withAnimation(.easeInOut(duration: 0.2)) {
                                    currentPath = ""
                                }
                            }) {
                                HStack(spacing: 4) {
                                    Image(systemName: "folder.fill")
                                    Text("Root")
                                }
                                .font(.system(size: 14, weight: currentPath == "" ? .bold : .medium))
                                .padding(.vertical, 6)
                                .padding(.horizontal, 12)
                                .background(currentPath == "" ? Color.accentColor : Color(.tertiarySystemBackground))
                                .foregroundColor(currentPath == "" ? .white : .primary)
                                .cornerRadius(12)
                            }
                            
                            let pathParts = currentPath.split(separator: "/").map(String.init)
                            ForEach(0..<pathParts.count, id: \.self) { idx in
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                
                                let targetPath = pathParts[0...idx].joined(separator: "/") + "/"
                                Button(action: {
                                    withAnimation(.easeInOut(duration: 0.2)) {
                                        currentPath = targetPath
                                    }
                                }) {
                                    Text(pathParts[idx])
                                        .font(.system(size: 14, weight: currentPath == targetPath ? .bold : .medium))
                                        .padding(.vertical, 6)
                                        .padding(.horizontal, 12)
                                        .background(currentPath == targetPath ? Color.accentColor : Color(.tertiarySystemBackground))
                                        .foregroundColor(currentPath == targetPath ? .white : .primary)
                                        .cornerRadius(12)
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                    .padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground))
                    
                    Divider()
                    
                    // Folder hierarchy list
                    let nodes = getNodesForCurrentPath()
                    List {
                        // "Go Up" shortcut if in a subdirectory
                        if !currentPath.isEmpty {
                            Button(action: {
                                withAnimation(.easeInOut(duration: 0.2)) {
                                    goBack()
                                }
                            }) {
                                HStack(spacing: 16) {
                                    Image(systemName: "arrow.turn.up.left")
                                        .font(.headline)
                                        .foregroundColor(.secondary)
                                        .frame(width: 32)
                                    Text(".. (Go Up)")
                                        .font(.body)
                                        .foregroundColor(.secondary)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                        
                        ForEach(nodes) { node in
                            Button(action: {
                                if node.isDirectory {
                                    withAnimation(.easeInOut(duration: 0.2)) {
                                        currentPath = node.fullName
                                    }
                                } else if let entry = node.entry {
                                    previewEntry(entry)
                                }
                            }) {
                                HStack(spacing: 16) {
                                    // Folder (yellow) or File (blue) Icon
                                    Image(systemName: node.isDirectory ? "folder.fill" : "doc.fill")
                                        .font(.title2)
                                        .foregroundColor(node.isDirectory ? .yellow : .blue)
                                        .frame(width: 32)
                                    
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(node.name)
                                            .font(.headline)
                                            .foregroundColor(.primary)
                                            .lineLimit(1)
                                        
                                        if !node.isDirectory {
                                            HStack {
                                                Text(formatSize(node.size))
                                                    .font(.caption)
                                                    .foregroundColor(.secondary)
                                                
                                                Text("•  CRC: \(String(format: "%08X", node.crc))")
                                                    .font(.caption)
                                                    .foregroundColor(.secondary)
                                            }
                                        }
                                    }
                                    
                                    Spacer()
                                    
                                    Image(systemName: node.isDirectory ? "chevron.right" : "eye")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .padding(.vertical, 6)
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(
                isPresented: $isFileImporterPresented,
                allowedContentTypes: [
                    .zip,
                    .gzip,
                    UTType(filenameExtension: "7z"),
                    UTType(filenameExtension: "tar")
                ].compactMap { $0 },
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    guard let url = urls.first else { return }
                    loadArchive(from: url)
                case .failure(let error):
                    self.errorMessage = "Failed to select file: \(error.localizedDescription)"
                }
            }
            .sheet(item: $selectedEntry) { entry in
                PreviewSheet(
                    entryName: entry.name,
                    entrySize: entry.size,
                    previewText: previewText,
                    previewImage: previewImage,
                    isExtracting: isExtracting
                )
            }
        }
    }
    
    // Parse the current path and return subdirectories and files in it
    private func getNodesForCurrentPath() -> [FilerNode] {
        var nodes: [FilerNode] = []
        var seenNames = Set<String>()
        
        for entry in entries {
            let name = entry.name
            
            // Exclude entries that do not match the current path prefix
            if !currentPath.isEmpty && !name.hasPrefix(currentPath) {
                continue
            }
            
            // Exclude the current path itself
            if name == currentPath {
                continue
            }
            
            // Get the relative path fragment inside the current directory
            let relativePath = String(name.dropFirst(currentPath.count))
            let parts = relativePath.split(separator: "/").map(String.init)
            
            guard let firstPart = parts.first else { continue }
            
            if seenNames.contains(firstPart) {
                continue
            }
            seenNames.insert(firstPart)
            
            // Determine if this item is a directory in the current scope
            // It's a directory if it has sub-paths, ends with "/", or is explicitly a directory
            let isDir = parts.count > 1 || name.hasSuffix("/") || entry.isDirectory
            
            let nodeFullName: String
            if isDir {
                nodeFullName = currentPath + firstPart + "/"
            } else {
                nodeFullName = name
            }
            
            nodes.append(FilerNode(
                id: nodeFullName,
                name: firstPart,
                fullName: nodeFullName,
                isDirectory: isDir,
                size: isDir ? 0 : entry.size,
                crc: isDir ? 0 : Int64(entry.crc),
                entry: isDir ? nil : entry
            ))
        }
        
        // Sort folders first, then files alphabetically
        return nodes.sorted { (n1, n2) -> Bool in
            if n1.isDirectory != n2.isDirectory {
                return n1.isDirectory
            }
            return n1.name.localizedCompare(n2.name) == .orderedAscending
        }
    }
    
    // Navigate one directory up
    private func goBack() {
        var parts = currentPath.split(separator: "/").map(String.init)
        if !parts.isEmpty {
            parts.removeLast()
            if parts.isEmpty {
                currentPath = ""
            } else {
                currentPath = parts.joined(separator: "/") + "/"
            }
        }
    }
    
    // Load archive using Kotlin Multiplatform SeekableSource and KioArch
    private func loadArchive(from url: URL) {
        isLoading = true
        errorMessage = nil
        entries = []
        fileName = url.lastPathComponent
        currentPath = "" // Reset to Root when loading a new file
        
        // Clean up previous active reader
        if let previousReader = activeReader {
            previousReader.close()
            activeReader = nil
        }
        
        // Access security-scoped iOS resource
        guard url.startAccessingSecurityScopedResource() else {
            errorMessage = "Permission denied to access the selected file."
            isLoading = false
            return
        }
        
        // Ensure we stop accessing security scope when exiting
        defer {
            url.stopAccessingSecurityScopedResource()
        }
        
        let path = url.path
        
        do {
            // Instantiate Swift implementation of Kotlin's SeekableSource
            guard let source = FileSeekableSource(path: path) else {
                errorMessage = "Failed to open file: \(url.lastPathComponent)"
                isLoading = false
                return
            }
            
            // Pass SeekableSource directly into Kotlin Multiplatform createReader
            let instance = KioArch()
            let reader = instance.createReader(source: source)
            
            self.activeReader = reader
            let rawEntries = reader.getEntries()
            
            // Convert Kotlin List directly to Swift Array
            self.entries = rawEntries
            self.isLoading = false
        } catch {
            self.errorMessage = "Failed to parse archive: \(error.localizedDescription)"
            self.isLoading = false
        }
    }
    
    // Extract and preview file content
    private func previewEntry(_ entry: ArchiveEntry) {
        guard let reader = activeReader else { return }
        
        self.previewText = nil
        self.previewImage = nil
        self.isExtracting = true
        self.selectedEntry = entry
        
        DispatchQueue.global(qos: .userInitiated).async {
            // Using the Kotlin API ArchiveEntry.extractToByteArray
            let ktBytes = entry.extractToByteArray(reader: reader)
            let data = ktBytes.toData()
            
            DispatchQueue.main.async {
                self.isExtracting = false
                
                // Determine preview type based on file extension
                let lowerName = entry.name.lowercased()
                if lowerName.hasSuffix(".png") || lowerName.hasSuffix(".jpg") || lowerName.hasSuffix(".jpeg") {
                    if let image = UIImage(data: data) {
                        self.previewImage = image
                    } else {
                        self.previewText = "Failed to render extracted image data."
                    }
                } else {
                    // Default to text preview (Try UTF-8, then Shift_JIS as fallback for Japanese text files)
                    if let text = String(data: data, encoding: .utf8) {
                        self.previewText = text
                    } else if let text = String(data: data, encoding: String.Encoding(rawValue: 0x80000a01)) {
                        // 0x80000a01 is Windows-31J / Shift_JIS
                        self.previewText = text
                    } else if let text = String(data: data, encoding: .japaneseEUC) {
                        self.previewText = text
                    } else {
                        self.previewText = "Binary file (Preview not available as UTF-8/Shift_JIS text)."
                    }
                }
            }
        }
    }
    
    // Helper to format file sizes
    private func formatSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useBytes, .useKB, .useMB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

// Custom detail sheet for content preview
struct PreviewSheet: View {
    let entryName: String
    let entrySize: Int64
    let previewText: String?
    let previewImage: UIImage?
    let isExtracting: Bool
    
    @Environment(\.presentationMode) var presentationMode

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Info block
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entryName)
                            .font(.headline)
                            .lineLimit(1)
                        Text(ByteCountFormatter().string(fromByteCount: entrySize))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                }
                .padding()
                .background(Color(.secondarySystemBackground))
                
                Divider()
                
                // Content area
                if isExtracting {
                    Spacer()
                    ProgressView("Extracting content using KioArch...")
                    Spacer()
                } else {
                    ScrollView {
                        if let image = previewImage {
                            VStack {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxWidth: .infinity)
                                    .cornerRadius(12)
                                    .shadow(radius: 6)
                                    .padding(24)
                                
                                Text("Preview Image (\(Int(image.size.width))x\(Int(image.size.height)))")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } else if let text = previewText {
                            VStack(alignment: .leading) {
                                Text(text)
                                    .font(.system(.body, design: .monospaced))
                                    .padding()
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        } else {
                            Spacer()
                            Text("No preview available.")
                                .foregroundColor(.secondary)
                            Spacer()
                        }
                    }
                }
            }
            .navigationTitle("File Preview")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarItems(trailing: Button("Close") {
                presentationMode.wrappedValue.dismiss()
            })
        }
    }
}

// Allow ArchiveEntry to conform to Identifiable for Sheets
extension ArchiveEntry: Identifiable {
    public var id: Int32 {
        return self.index
    }
}
