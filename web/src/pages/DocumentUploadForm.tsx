import { type ChangeEvent, type FormEvent, useState } from "react";

import { ApiError } from "../api/client";
import { useAddDocument } from "../api/hooks";
import { ALL_ROLES, ROLE_LABELS, type Role } from "../api/types";
import { Alert, Button, Field, inputClass } from "../components/ui";

const MAX_CONTENT_LENGTH = 200_000;

export function DocumentUploadForm() {
  const add = useAddDocument();
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [roles, setRoles] = useState<Role[]>([...ALL_ROLES]);
  const [fileError, setFileError] = useState<string | null>(null);
  const apiError = add.error instanceof ApiError ? add.error : null;

  async function loadFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    setFileError(null);
    if (!file) return;
    const text = await file.text();
    if (text.length > MAX_CONTENT_LENGTH) {
      setFileError(`That file is too long (max ${MAX_CONTENT_LENGTH.toLocaleString()} characters).`);
      return;
    }
    setContent(text);
    if (!title) setTitle(file.name.replace(/\.(txt|md)$/i, ""));
  }

  function toggleRole(role: Role) {
    setRoles((current) => (current.includes(role) ? current.filter((r) => r !== role) : [...current, role]));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    add.mutate(
      { title: title.trim(), content, allowedRoles: roles },
      {
        onSuccess: () => {
          setTitle("");
          setContent("");
          setRoles([...ALL_ROLES]);
        },
      },
    );
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field label="Title" htmlFor="doc-title" error={apiError?.messageFor("title")}>
        <input
          id="doc-title"
          className={inputClass}
          required
          maxLength={200}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
        />
      </Field>

      <Field label="Content" htmlFor="doc-content" hint="Paste text, or load a .txt / .md file below." error={apiError?.messageFor("content") ?? fileError ?? undefined}>
        <textarea
          id="doc-content"
          className={inputClass}
          required
          rows={6}
          maxLength={MAX_CONTENT_LENGTH}
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
      </Field>

      <input
        type="file"
        accept=".txt,.md,text/plain,text/markdown"
        onChange={(e) => void loadFile(e)}
        aria-label="Load document from file"
        className="block w-full text-xs text-slate-500 file:mr-3 file:rounded-md file:border-0 file:bg-slate-100 file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-slate-700 hover:file:bg-slate-200"
      />

      <fieldset>
        <legend className="text-sm font-medium text-slate-700">Visible to</legend>
        <div className="mt-2 flex flex-wrap gap-4">
          {ALL_ROLES.map((role) => (
            <label key={role} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                className="size-4 rounded border-slate-300 accent-indigo-600"
                checked={roles.includes(role)}
                onChange={() => toggleRole(role)}
              />
              {ROLE_LABELS[role]}
            </label>
          ))}
        </div>
      </fieldset>

      {add.isSuccess && (
        <Alert tone="success">Document added ({add.data.chunks} searchable {add.data.chunks === 1 ? "section" : "sections"}).</Alert>
      )}
      {apiError && apiError.fieldErrors.length === 0 && <Alert tone="error">{apiError.message}</Alert>}

      <Button type="submit" className="w-full" disabled={add.isPending || roles.length === 0}>
        {add.isPending ? "Adding…" : roles.length === 0 ? "Choose at least one role" : "Add document"}
      </Button>
    </form>
  );
}
