#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <mutex>

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
    // PB-003: per-page character boxes for a selectable text layer.
    // Returns JSON { page, width, height, chars:[{x,y,w,h,c}] } or "" on failure.
    std::string ExtractTextLayer(int id, uint32_t pageIndex);
    void Close(int id);

private:
    struct DocEntry {
        int id;
        std::string filePath;
        uint32_t pageCount;
        std::vector<PdfPageBounds> pageBounds;
        std::vector<std::string> tempFiles;
    };
    // PB-020: Open/RenderPage/ExtractText now run on JobService workers, so
    // serialize access to m_docs/tempFiles (mutool itself runs out of process).
    mutable std::mutex m_mutex;
    std::vector<DocEntry> m_docs;
    std::vector<std::string> m_tempFiles;
    int m_nextId = 1;

    std::string GetMutoolPath();
    bool RunMutool(const std::string& args, std::string& output, int timeoutMs = 30000);
};

class BridgeServer;
namespace pb { class JobService; }
void RegisterPdfHandlers(BridgeServer* bridge, PdfService* pdf, pb::JobService* jobs);
