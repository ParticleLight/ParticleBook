#pragma once
#include <string>
#include <vector>
#include "nlohmann/json.hpp"

using json = nlohmann::json;

struct ExtractedMetadata {
    std::string title;
    std::string author;
    std::string language;
    std::string description;
    std::string publisher;
    std::string isbn;
    std::string coverFile;   // path inside EPUB zip
    std::string coverPath;   // external file path for cover
};

// PB-008-A：DOCX（OOXML）转换结果。html 是自包含 HTML（图片内联 data URL），
// coverData/coverExt 是第一张图片的原始字节，交给导入流程落盘当封面。
struct DocxConversion {
    bool ok = false;
    std::string error;          // missing_document / bad_xml / document_too_large / empty_document
    std::string html;
    std::string title;
    std::string author;
    std::string language;
    std::string coverData;
    std::string coverExt;       // png / jpg / gif / webp
    int imageCount = 0;
    int paragraphCount = 0;
};

class LibraryService {
public:
    bool ExtractEpubMetadata(const std::string& filePath, ExtractedMetadata& meta);
    bool ExtractPdfMetadata(const std::string& filePath, ExtractedMetadata& meta);
    bool ExtractFb2Metadata(const std::string& filePath, ExtractedMetadata& meta);

    // Extract cover image from EPUB to external file, returns path
    std::string ExtractEpubCover(const std::string& filePath, const std::string& coverFile);

    // Render first page of PDF as cover image, returns path
    std::string ExtractPdfCover(const std::string& filePath);

    // PB-008-A：DOCX → 自包含 HTML（段落/标题/粗斜体/表格/内联图片）。
    // 只解 OOXML，不依赖 libxml/Word；失败时 error 说明原因。
    DocxConversion ConvertDocxToHtml(const std::string& filePath);

private:
    std::string FindOpfPath(const std::string& epubPath);
    bool ParseOpfXml(const std::string& xml, const std::string& opfDir, ExtractedMetadata& meta);
};

std::string DetectFormat(const std::string& path);
