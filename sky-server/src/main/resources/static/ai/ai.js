(function () {
    'use strict';

    var API = '/ai/api';
    var MAX_QUESTION = 4000;
    var MAX_CONTENT = 20000;

    var token = '';
    var controller = null;
    var streaming = false;
    var toastTimer = null;

    var messagesEl = document.getElementById('messages');
    var emptyState = document.getElementById('emptyState');
    var chatForm = document.getElementById('chatForm');
    var questionEl = document.getElementById('question');
    var counterEl = document.getElementById('counter');
    var sendBtn = document.getElementById('sendBtn');
    var stopBtn = document.getElementById('stopBtn');
    var retrievalOnlyEl = document.getElementById('retrievalOnly');
    var sourceList = document.getElementById('sourceList');
    var sourceEmpty = document.getElementById('sourceEmpty');
    var refreshBtn = document.getElementById('refreshBtn');
    var statusChunks = document.getElementById('statusChunks');
    var statusModel = document.getElementById('statusModel');
    var statusRetrieval = document.getElementById('statusRetrieval');
    var tokenBtn = document.getElementById('tokenBtn');
    var tokenModal = document.getElementById('tokenModal');
    var tokenForm = document.getElementById('tokenForm');
    var tokenInput = document.getElementById('tokenInput');
    var tokenMsg = document.getElementById('tokenMsg');
    var docForm = document.getElementById('docForm');
    var docTitle = document.getElementById('docTitle');
    var docSource = document.getElementById('docSource');
    var docContent = document.getElementById('docContent');
    var docCounter = document.getElementById('docCounter');
    var docMsg = document.getElementById('docMsg');
    var toastEl = document.getElementById('toast');

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.textContent = text;
        }
        return node;
    }

    function showToast(text) {
        toastEl.textContent = text;
        toastEl.hidden = false;
        if (toastTimer) {
            window.clearTimeout(toastTimer);
        }
        toastTimer = window.setTimeout(function () {
            toastEl.hidden = true;
        }, 2800);
    }

    function scrollToBottom() {
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function hideEmptyState() {
        if (emptyState) {
            emptyState.hidden = true;
        }
    }

    function request(path, options) {
        var opts = options || {};
        var headers = { 'X-AI-Token': token };
        if (opts.body) {
            headers['Content-Type'] = 'application/json';
        }
        return fetch(API + path, {
            method: opts.method || 'GET',
            headers: headers,
            body: opts.body
        }).then(function (response) {
            return response.text().then(function (text) {
                var data = null;
                if (text) {
                    try {
                        data = JSON.parse(text);
                    } catch (ignore) {
                        data = null;
                    }
                }
                if (!response.ok) {
                    var error = new Error((data && data.message) || ('请求失败（' + response.status + '）'));
                    error.status = response.status;
                    error.code = data && data.code;
                    if (response.status === 401) {
                        error.code = 'UNAUTHORIZED';
                    }
                    throw error;
                }
                return data;
            });
        });
    }

    function setStreaming(value) {
        streaming = value;
        sendBtn.disabled = value;
        stopBtn.hidden = !value;
    }

    function updateCounter() {
        counterEl.textContent = questionEl.value.length + ' / ' + MAX_QUESTION;
    }

    function updateDocCounter() {
        docCounter.textContent = docContent.value.length + ' / ' + MAX_CONTENT;
    }

    function setPill(node, text, tone) {
        node.textContent = text;
        node.classList.remove('warn');
        node.classList.remove('bad');
        if (tone) {
            node.classList.add(tone);
        }
    }

    function renderCitation(citation, index) {
        var details = el('details', 'citation');
        var summary = el('summary');
        summary.appendChild(el('span', 'citation-head',
            '[' + index + '] ' + (citation.title || '未命名资料')));
        var score = typeof citation.score === 'number' ? citation.score.toFixed(3) : '-';
        summary.appendChild(el('span', 'citation-meta',
            '来源 ' + (citation.source || '-') + ' · 块 ' + (citation.ordinal || '-') + ' · 相似度 ' + score));
        details.appendChild(summary);
        details.appendChild(el('pre', null, citation.content || ''));
        return details;
    }

    function createAssistantMessage() {
        var wrap = el('article', 'msg assistant');
        var bubble = el('div', 'bubble');
        var textEl = el('div', 'answer-text');
        var noticeEl = el('div', 'notice');
        var errorEl = el('div', 'error-text');
        var citationsTitle = el('p', 'citations-title', '引用片段');
        var citationList = el('div', 'citation-list');
        noticeEl.hidden = true;
        errorEl.hidden = true;
        citationsTitle.hidden = true;
        bubble.appendChild(textEl);
        bubble.appendChild(noticeEl);
        bubble.appendChild(errorEl);
        bubble.appendChild(citationsTitle);
        bubble.appendChild(citationList);
        wrap.appendChild(bubble);
        messagesEl.appendChild(wrap);
        scrollToBottom();

        return {
            appendText: function (chunk) {
                if (!chunk) {
                    return;
                }
                textEl.textContent += chunk;
                scrollToBottom();
            },
            renderMeta: function (meta) {
                var citations = meta && Array.isArray(meta.citations) ? meta.citations : [];
                citationList.textContent = '';
                citations.forEach(function (citation, index) {
                    citationList.appendChild(renderCitation(citation, index + 1));
                });
                citationsTitle.hidden = citations.length === 0;
                if (meta && meta.chatEnabled === false) {
                    setPill(statusModel, '未配置生成模型', 'warn');
                }
                scrollToBottom();
            },
            setNotice: function (message) {
                noticeEl.textContent = message;
                noticeEl.hidden = false;
                scrollToBottom();
            },
            setError: function (message) {
                errorEl.textContent = message;
                errorEl.hidden = false;
                scrollToBottom();
            },
            finish: function () {
                if (!textEl.textContent && noticeEl.hidden && errorEl.hidden) {
                    noticeEl.textContent = '没有收到有效回复，请重试。';
                    noticeEl.hidden = false;
                }
                scrollToBottom();
            }
        };
    }

    function appendUserMessage(question) {
        var wrap = el('article', 'msg user');
        wrap.appendChild(el('div', 'bubble', question));
        messagesEl.appendChild(wrap);
        scrollToBottom();
    }

    function streamChat(question, assistant, signal) {
        return fetch(API + '/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-AI-Token': token
            },
            body: JSON.stringify({ question: question }),
            signal: signal
        }).then(function (response) {
            if (response.status === 401) {
                var unauthorized = new Error('访问令牌无效');
                unauthorized.code = 'UNAUTHORIZED';
                throw unauthorized;
            }
            if (!response.ok) {
                return response.text().then(function (text) {
                    var data = null;
                    try {
                        data = text ? JSON.parse(text) : null;
                    } catch (ignore) {
                        data = null;
                    }
                    var error = new Error((data && data.message) || ('请求失败（' + response.status + '）'));
                    error.status = response.status;
                    error.code = data && data.code;
                    throw error;
                });
            }
            if (!response.body) {
                throw new Error('当前浏览器不支持流式响应');
            }
            return readEventStream(response.body, assistant);
        });
    }

    function readEventStream(body, assistant) {
        var reader = body.getReader();
        var decoder = new TextDecoder('utf-8');
        var buffer = '';
        var eventName = '';
        var dataLines = [];
        var finished = false;

        function handleEvent(name, payload) {
            var data;
            try {
                data = JSON.parse(payload);
            } catch (ignore) {
                return false;
            }
            if (name === 'meta') {
                assistant.renderMeta(data);
            } else if (name === 'delta') {
                assistant.appendText(String(data.text || ''));
            } else if (name === 'notice') {
                assistant.setNotice(String(data.message || ''));
            } else if (name === 'error') {
                assistant.setError(String(data.message || '模型服务暂时不可用'));
            } else if (name === 'done') {
                return true;
            }
            return false;
        }

        function dispatch() {
            if (!dataLines.length) {
                eventName = '';
                return;
            }
            var name = eventName || 'message';
            var payload = dataLines.join('\n');
            eventName = '';
            dataLines = [];
            if (handleEvent(name, payload)) {
                finished = true;
            }
        }

        function pump() {
            if (finished) {
                return Promise.resolve();
            }
            return reader.read().then(function (chunk) {
                if (chunk.done) {
                    return undefined;
                }
                buffer += decoder.decode(chunk.value, { stream: true });
                var index;
                while ((index = buffer.indexOf('\n')) >= 0) {
                    var line = buffer.slice(0, index);
                    buffer = buffer.slice(index + 1);
                    if (line.charAt(line.length - 1) === '\r') {
                        line = line.slice(0, -1);
                    }
                    if (line === '') {
                        dispatch();
                        if (finished) {
                            break;
                        }
                        continue;
                    }
                    if (line.charAt(0) === ':') {
                        continue;
                    }
                    var colon = line.indexOf(':');
                    var field = colon === -1 ? line : line.slice(0, colon);
                    var value = colon === -1 ? '' : line.slice(colon + 1);
                    if (value.charAt(0) === ' ') {
                        value = value.slice(1);
                    }
                    if (field === 'event') {
                        eventName = value;
                    } else if (field === 'data') {
                        dataLines.push(value);
                    }
                }
                return pump();
            });
        }

        return pump().then(function () {
            if (!finished) {
                try {
                    reader.cancel();
                } catch (ignore) {
                    // 已断开时取消可能失败
                }
            }
            assistant.finish();
        });
    }

    function startChat(question) {
        hideEmptyState();
        appendUserMessage(question);
        questionEl.value = '';
        updateCounter();
        var assistant = createAssistantMessage();
        controller = new AbortController();
        setStreaming(true);

        var only = retrievalOnlyEl.checked;
        var task;
        if (only) {
            task = request('/search', {
                method: 'POST',
                body: JSON.stringify({ question: question })
            }).then(function (data) {
                assistant.renderMeta(data);
                var citations = data && Array.isArray(data.citations) ? data.citations : [];
                assistant.setNotice(citations.length
                    ? '仅检索模式：以下为资料原文，未调用生成模型。'
                    : '知识库中没有找到相关资料。');
                assistant.finish();
            });
        } else {
            task = streamChat(question, assistant, controller.signal);
        }

        task.catch(function (error) {
            if (error && error.name === 'AbortError') {
                assistant.setNotice('已停止生成。');
            } else if (error && error.code === 'UNAUTHORIZED') {
                token = '';
                openTokenModal('令牌无效，请重新输入');
                assistant.setError('访问令牌无效');
            } else {
                assistant.setError((error && error.message) || '请求失败，请稍后重试');
            }
            assistant.finish();
        }).then(function () {
            setStreaming(false);
            controller = null;
        });
    }

    function refreshStatus() {
        return request('/status').then(function (data) {
            setPill(statusChunks, '片段 ' + (data.chunkCount || 0), data.ready === false ? 'bad' : null);
            setPill(statusModel, data.chatEnabled ? '生成模型已启用' : '未配置生成模型',
                data.chatEnabled ? null : 'warn');
            setPill(statusRetrieval, data.retrieval || '检索方式 --', null);
        });
    }

    function renderSources(items) {
        sourceList.textContent = '';
        items.forEach(function (item) {
            var li = el('li', 'source-item');
            var main = el('div', 'source-main');
            main.appendChild(el('div', 'source-title', item.title || '未命名资料'));
            main.appendChild(el('div', 'source-meta',
                (item.source || '-') + ' · ' + (item.chunkCount || 0) + ' 个片段'));
            var remove = el('button', 'mini-btn', '删除');
            remove.type = 'button';
            remove.addEventListener('click', function () {
                if (!window.confirm('确定删除《' + (item.title || item.source) + '》及其全部片段？')) {
                    return;
                }
                remove.disabled = true;
                request('/knowledge?source=' + encodeURIComponent(item.source), { method: 'DELETE' })
                    .then(function () {
                        showToast('已删除该资料');
                        return loadAll();
                    })
                    .catch(function (error) {
                        if (error && error.code === 'UNAUTHORIZED') {
                            token = '';
                            openTokenModal('令牌无效，请重新输入');
                        } else {
                            showToast((error && error.message) || '删除失败');
                        }
                        remove.disabled = false;
                    });
            });
            li.appendChild(main);
            li.appendChild(remove);
            sourceList.appendChild(li);
        });
        sourceEmpty.hidden = items.length > 0;
    }

    function loadKnowledge() {
        return request('/knowledge').then(function (data) {
            renderSources(data && Array.isArray(data.items) ? data.items : []);
        });
    }

    function loadAll() {
        return Promise.all([refreshStatus(), loadKnowledge()]).catch(function (error) {
            if (error && error.code === 'UNAUTHORIZED') {
                token = '';
                openTokenModal('令牌无效，请重新输入');
                return;
            }
            if (error && error.code === 'AI_DISABLED') {
                setPill(statusChunks, 'AI 未启用', 'bad');
                setPill(statusModel, 'AI 未启用', 'bad');
                setPill(statusRetrieval, '检索不可用', 'bad');
            }
            showToast((error && error.message) || '加载失败');
        });
    }

    function openTokenModal(message) {
        tokenModal.hidden = false;
        tokenInput.value = '';
        if (message) {
            tokenMsg.textContent = message;
            tokenMsg.hidden = false;
            tokenMsg.classList.add('bad');
        } else {
            tokenMsg.hidden = true;
            tokenMsg.classList.remove('bad');
        }
        tokenInput.focus();
    }

    function setDocMessage(message, bad) {
        docMsg.textContent = message;
        docMsg.hidden = false;
        if (bad) {
            docMsg.classList.add('bad');
        } else {
            docMsg.classList.remove('bad');
        }
    }

    chatForm.addEventListener('submit', function (event) {
        event.preventDefault();
        if (streaming) {
            return;
        }
        var question = questionEl.value.trim();
        if (!question) {
            showToast('请输入问题');
            return;
        }
        if (question.length > MAX_QUESTION) {
            showToast('问题不能超过 4000 字');
            return;
        }
        if (!token) {
            openTokenModal('请先输入访问令牌');
            return;
        }
        startChat(question);
    });

    questionEl.addEventListener('input', updateCounter);
    questionEl.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            if (typeof chatForm.requestSubmit === 'function') {
                chatForm.requestSubmit();
            } else {
                chatForm.dispatchEvent(new Event('submit', { cancelable: true }));
            }
        }
    });

    stopBtn.addEventListener('click', function () {
        if (controller) {
            controller.abort();
        }
    });

    tokenBtn.addEventListener('click', function () {
        openTokenModal(null);
    });

    tokenForm.addEventListener('submit', function (event) {
        event.preventDefault();
        var value = tokenInput.value.trim();
        if (!value) {
            tokenMsg.textContent = '令牌不能为空';
            tokenMsg.hidden = false;
            tokenMsg.classList.add('bad');
            return;
        }
        token = value;
        tokenModal.hidden = true;
        loadAll();
    });

    refreshBtn.addEventListener('click', function () {
        loadAll();
    });

    docContent.addEventListener('input', updateDocCounter);

    docForm.addEventListener('submit', function (event) {
        event.preventDefault();
        var title = docTitle.value.trim();
        var content = docContent.value.trim();
        var source = docSource.value.trim();
        if (!title) {
            setDocMessage('请填写标题', true);
            return;
        }
        if (title.length > 100) {
            setDocMessage('标题不能超过 100 字', true);
            return;
        }
        if (!content) {
            setDocMessage('请填写内容', true);
            return;
        }
        if (content.length > MAX_CONTENT) {
            setDocMessage('内容不能超过 20000 字', true);
            return;
        }
        var body = { title: title, content: content };
        if (source) {
            body.source = source;
        }
        request('/knowledge', { method: 'POST', body: JSON.stringify(body) })
            .then(function (data) {
                setDocMessage('已导入《' + data.title + '》，共 ' + data.chunkCount + ' 个片段。', false);
                docTitle.value = '';
                docSource.value = '';
                docContent.value = '';
                updateDocCounter();
                return loadAll();
            })
            .catch(function (error) {
                if (error && error.code === 'UNAUTHORIZED') {
                    token = '';
                    openTokenModal('令牌无效，请重新输入');
                    return;
                }
                setDocMessage((error && error.message) || '导入失败', true);
            });
    });

    updateCounter();
    updateDocCounter();
    openTokenModal('请输入访问令牌以开始使用');
})();
