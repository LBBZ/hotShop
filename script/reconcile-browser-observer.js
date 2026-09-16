// Test-only passive observation of real fetch streams. No route interception or fake response.
(() => {
  const originalFetch = window.fetch.bind(window);
  window.fetch = async (...args) => {
    const response = await originalFetch(...args);
    if (/\/agent\/runs\/[^/]+\/events(?:\?|$)/.test(response.url)) {
      const reader = response.clone().body?.getReader();
      if (reader) {
        void (async () => {
          const decoder = new TextDecoder();
          let pending = "";
          try {
            while (true) {
              const { done, value } = await reader.read();
              if (done) break;
              pending += decoder.decode(value, { stream: true });
              const lines = pending.split("\n");
              pending = lines.pop() ?? "";
              for (const line of lines) {
                if (!line.startsWith("data: ")) continue;
                const event = JSON.parse(line.slice(6));
                if (!["rag.completed", "done"].includes(event.type)) continue;
                const records = JSON.parse(document.documentElement.dataset.deliveryRagEvents ?? "[]");
                // No message text, credentials, exception message or other tool data retained.
                records.push({ type: event.type, runId: event.runId,
                  data: event.type === "rag.completed" ? event.data : {} });
                document.documentElement.dataset.deliveryRagEvents = JSON.stringify(records.slice(-32));
              }
            }
          } catch {
            // Application may abort an already-completed SSE stream; assertions require real events.
          } finally {
            reader.releaseLock();
          }
        })();
      }
    }
    return response;
  };
})();
