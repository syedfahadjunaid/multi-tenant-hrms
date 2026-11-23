import { type FormEvent, type KeyboardEvent, useState } from "react";

import { ApiError } from "../api/client";
import { useAsk, useCurrentUser } from "../api/hooks";
import type { AskResponse } from "../api/types";
import { Alert, Badge, Button, Card, PageHeader, Spinner, inputClass } from "../components/ui";
import { DocumentUploadForm } from "./DocumentUploadForm";

const MAX_QUESTION_LENGTH = 2000;

type Exchange = {
  id: number;
  question: string;
  response?: AskResponse;
  error?: ApiError | Error;
};

export function AssistantPage() {
  const user = useCurrentUser();
  const ask = useAsk();
  const [question, setQuestion] = useState("");
  const [exchanges, setExchanges] = useState<Exchange[]>([]);

  function submit(event?: FormEvent) {
    event?.preventDefault();
    const text = question.trim();
    if (!text || ask.isPending) return;

    const id = Date.now();
    setExchanges((list) => [{ id, question: text }, ...list]);
    setQuestion("");
    ask.mutate(text, {
      onSuccess: (response) => setExchanges((list) => list.map((x) => (x.id === id ? { ...x, response } : x))),
      onError: (error) => setExchanges((list) => list.map((x) => (x.id === id ? { ...x, error } : x))),
    });
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) submit(event); // Shift+Enter for a new line
  }

  return (
    <>
      <PageHeader
        title="Policy assistant"
        description="Answers come only from HR documents your role is allowed to see."
      />

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <form onSubmit={submit} className="space-y-3">
              <label htmlFor="question" className="text-sm font-medium text-slate-700">
                Your question
              </label>
              <textarea
                id="question"
                rows={3}
                maxLength={MAX_QUESTION_LENGTH}
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                onKeyDown={onKeyDown}
                placeholder="e.g. How many days of annual leave do I get?"
                className={inputClass}
              />
              <div className="flex items-center justify-between">
                <span className="text-xs text-slate-500">
                  {question.length}/{MAX_QUESTION_LENGTH} · Enter to send, Shift+Enter for a new line
                </span>
                <Button type="submit" disabled={!question.trim() || ask.isPending}>
                  {ask.isPending ? "Thinking…" : "Ask"}
                </Button>
              </div>
            </form>
          </Card>

          <ol aria-live="polite" className="space-y-4">
            {exchanges.map((x) => (
              <li key={x.id}>
                <ExchangeCard exchange={x} />
              </li>
            ))}
          </ol>
        </div>

        {user.hasAnyRole("HR_ADMIN") && (
          <aside>
            <Card>
              <h2 className="font-semibold">Add a policy document</h2>
              <p className="mt-1 mb-4 text-sm text-slate-500">
                Choose which roles may see it. The assistant never shows it to anyone else.
              </p>
              <DocumentUploadForm />
            </Card>
          </aside>
        )}
      </div>
    </>
  );
}

function ExchangeCard({ exchange }: { exchange: Exchange }) {
  const { question, response, error } = exchange;
  return (
    <Card>
      <p className="font-medium">{question}</p>
      <div className="mt-3 border-t border-slate-100 pt-3">
        {error ? (
          <Alert tone="error">{errorMessage(error)}</Alert>
        ) : !response ? (
          <Spinner label="Searching documents" />
        ) : (
          <Answer response={response} />
        )}
      </div>
    </Card>
  );
}

function Answer({ response }: { response: AskResponse }) {
  return (
    <div className="space-y-3">
      {/* LLM output is untrusted text. Render it as plain text, never as HTML. */}
      <p className="text-sm leading-relaxed whitespace-pre-wrap text-slate-800">{response.answer}</p>
      {(response.sources.length > 0 || response.model !== "none") && (
        <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500">
          {response.sources.length > 0 && "Sources:"}
          {response.sources.map((s, i) => (
            <Badge key={s.documentId} tone="indigo">
              [{i + 1}] {s.title} · {Math.round(s.score * 100)}% match
            </Badge>
          ))}
          {response.model !== "none" && (
            <span className="ml-auto">
              {response.model === "offline" ? "Offline mode (no LLM configured)" : `Answered by ${response.model}`}
            </span>
          )}
        </div>
      )}
    </div>
  );
}

function errorMessage(error: Error): string {
  if (error instanceof ApiError && error.status === 429) {
    const wait = error.retryAfterSeconds ? ` in ${error.retryAfterSeconds} seconds` : " in a minute";
    return `You've reached the limit of questions per minute. Try again${wait}.`;
  }
  return error.message;
}
