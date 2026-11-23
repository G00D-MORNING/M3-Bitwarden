import Foundation

/// Minimal ASN.1 DER writer, enough to build a PKCS#10 certificate signing request.
enum DER {
    static func sequence(_ parts: [Data]) -> Data { tlv(0x30, parts.reduce(Data(), +)) }
    static func set(_ parts: [Data]) -> Data { tlv(0x31, parts.reduce(Data(), +)) }
    static func integer(_ value: UInt8) -> Data { tlv(0x02, Data([value])) }
    static func utf8(_ text: String) -> Data { tlv(0x0C, Data(text.utf8)) }
    static func bitString(_ bytes: Data) -> Data { tlv(0x03, Data([0x00]) + bytes) }
    static func contextConstructed(_ tag: UInt8, _ content: Data) -> Data { tlv(0xA0 | tag, content) }

    static func oid(_ dotted: String) -> Data {
        let arcs = dotted.split(separator: ".").compactMap { UInt64($0) }
        var body = Data([UInt8(arcs[0] * 40 + arcs[1])])
        for arc in arcs.dropFirst(2) {
            var chunk = [UInt8(arc & 0x7F)]
            var rest = arc >> 7
            while rest > 0 {
                chunk.insert(UInt8(rest & 0x7F) | 0x80, at: 0)
                rest >>= 7
            }
            body.append(contentsOf: chunk)
        }
        return tlv(0x06, body)
    }

    static func tlv(_ tag: UInt8, _ content: Data) -> Data {
        var out = Data([tag])
        let n = content.count
        if n < 0x80 {
            out.append(UInt8(n))
        } else {
            var len = [UInt8]()
            var rest = n
            while rest > 0 {
                len.insert(UInt8(rest & 0xFF), at: 0)
                rest >>= 8
            }
            out.append(0x80 | UInt8(len.count))
            out.append(contentsOf: len)
        }
        out.append(content)
        return out
    }

    static func pem(_ der: Data, label: String) -> String {
        let b64 = der.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
        return "-----BEGIN \(label)-----\n\(b64)\n-----END \(label)-----\n"
    }

    /// Decodes the first PEM block (or bare base64) in [text].
    static func fromPEM(_ text: String) -> Data? {
        let body = text
            .components(separatedBy: .newlines)
            .filter { !$0.hasPrefix("-----") }
            .joined()
            .trimmingCharacters(in: .whitespaces)
        return Data(base64Encoded: body)
    }
}
