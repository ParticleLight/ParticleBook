#pragma once
#include <string>
#include <vector>
#include <cstdint>

struct PdfPageBounds {
    double width;
    double height;
};

struct PdfOpenResult {
    int id;
    uint32_t pageCount;
    std::vector<PdfPageBounds> pageBounds;
};

class PdfService {
public:
    PdfService();
    ~PdfService();

    PdfOpenResult Open(const std::string& filePath);
    std::string RenderPage(int id, uint32_t pageIndex, int pixelWidth, int pixelHeight);
    std::string ExtractText(int id);
    void Close(int id);

private:
    struct DocEntry {
        int id;
        std::string filePath;
        uint32_t pageCount;
        std::vector<PdfPageBounds> pageBounds;
        std::vector<std::string> tempFiles;
    };
    std::vector<DocEntry> m_docs;
    std::vector<std::string> m_tempFiles;
    int m_nextId = 1;

    std::string GetMutoolPath();
    bool RunMutool(const std::string& args, std::string& output, int timeoutMs = 30000);
};

class BridgeServer;
void RegisterPdfHandlers(BridgeServer* bridge, PdfService* pdf);
