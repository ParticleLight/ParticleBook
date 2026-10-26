#pragma once
#include <string>
#include <functional>
#include <unordered_map>
#include <set>
#include <vector>
#include "nlohmann/json.hpp"

using json = nlohmann::json;

class WebViewHost;

class BridgeServer {
public:
    using MethodHandler = std::function<json(const json& params)>;
    using AsyncMethodHandler = std::function<void(
        const json& params,
        std::function<void(json)> respond,
        std::function<void(std::string)> reject)>;

    void RegisterMethod(const std::string& name, MethodHandler handler);
    void RegisterAsyncMethod(const std::string& name, AsyncMethodHandler handler);
    void HandleMessage(const std::string& rawJson, const std::string& source);

    void EmitEvent(const std::string& event, const json& data);

    // Returns the JS bridge script to inject
    static std::string GenerateBridgeScript();

    void SetWebView(WebViewHost* host) { m_webview = host; }
    WebViewHost* GetWebViewHost() const { return m_webview; }

    // Directly invoke a registered method (used for cross-service calls)
    json InvokeMethod(const std::string& method, const json& params);

private:
    void ProcessInvoke(int id, const std::string& method, const json& params);
    void SendResponse(int id, const json& result, const std::string& error);
    void ProcessSubscribe(const std::string& event);
    void ProcessUnsubscribe(const std::string& event);

    std::unordered_map<std::string, MethodHandler> m_methods;
    std::unordered_map<std::string, AsyncMethodHandler> m_asyncMethods;
    std::unordered_map<std::string, std::set<int>> m_subscribers;
    WebViewHost* m_webview = nullptr;
};
