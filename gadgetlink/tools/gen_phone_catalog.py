#!/usr/bin/env python3
"""Gera PhoneCatalog.kt (os 29 stubs phone.*) a partir do tools/list real do servidor mika.

Entrada: gadgetlink/src/test/resources/mika_tools.json (saída de tools/mika-dump/dump_mika_tools.sh).
Saída: gadgetlink/src/main/kotlin/com/darlisonfarich/gadgetlink/commands/PhoneCatalog.kt

Regras (especificação Gadget Link §2.1/§2.3):
  * correspondência 1:1 com as ferramentas mika, mais o seletor de árvore do Gadget Link;
  * os parâmetros viram {type, description} com os seis tipos fechados do protocolo; o que o JSON
    Schema do mika diz além do tipo (mínimo/máximo, tamanho, formato, itens) vai para o fim da
    descrição, para nada da semântica se perder;
  * required do mika -> required; o resto das properties -> optional;
  * writes = não readOnlyHint (o portão local exige a escrita liberada).

Uso: gen_phone_catalog.py [mika_tools.json] > PhoneCatalog.kt
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_IN = os.path.join(HERE, "..", "src", "test", "resources", "mika_tools.json")

# mika -> (comando gadget, timeout_ms imposto). Ordem = ordem do tools/list do mika.
MAPPING = {
    "mika_run_task": ("phone.run_task", 15_000),
    "mika_task_status": ("phone.task_status", 10_000),
    "mika_wait_task": ("phone.wait_task", 55_000),  # espera até MAX_WAIT_S=50 s
    "mika_answer": ("phone.answer_task", 10_000),
    "mika_steer_task": ("phone.steer_task", 10_000),
    "mika_cancel_task": ("phone.cancel_task", 10_000),
    "mika_list_tasks": ("phone.list_tasks", 10_000),
    "mika_device_state": ("phone.device_state", 10_000),
    "mika_wake_device": ("phone.wake", 15_000),
    "mika_unlock_device": ("phone.unlock", 30_000),
    "phone_status": ("phone.status", 5_000),
    "phone_get_state": ("phone.get_state", 5_000),
    "phone_reconcile": ("phone.reconcile", 10_000),
    "phone_observe": ("phone.get_tree", 30_000),
    "phone_screenshot": ("phone.get_screenshot", 30_000),
    "phone_list_apps": ("phone.list_apps", 15_000),
    "phone_approval_issue": ("phone.approval_issue", 10_000),
    "phone_launch_app": ("phone.open_app", 30_000),
    "phone_tap": ("phone.tap", 30_000),
    "phone_type": ("phone.type_text", 30_000),
    "phone_scroll": ("phone.scroll", 30_000),
    "phone_swipe": ("phone.swipe", 30_000),
    "phone_gesture": ("phone.gesture", 30_000),
    "phone_key": ("phone.press_key", 30_000),
    "phone_volume": ("phone.volume", 30_000),
    "phone_media": ("phone.media", 30_000),
    "phone_back": ("phone.press_back", 30_000),
    "phone_run": ("phone.run_batch", 300_000),
    "phone_cancel": ("phone.cancel", 10_000),
}

# Descrições para parâmetros que o mika declara sem "description" (só o tipo).
MISSING_DESCRIPTIONS = {
    ("phone_launch_app", "cold"): "Restart the app first (default false).",
    ("phone_type", "pressEnter"): "Press Enter after typing (may submit content).",
    ("mika_steer_task", "text"): "Instructions to add to the running task.",
    ("phone_gesture", "paths"): "Gesture paths: each path is a list of [x, y] points, normalized 0–999.",
}

TYPES = {"string", "integer", "number", "boolean", "object", "array"}


def shape(schema):
    """Resumo legível do que o JSON Schema diz além do tipo."""
    t = schema.get("type")
    notes = []
    if "minimum" in schema or "maximum" in schema:
        notes.append(f"{schema.get('minimum', '')}–{schema.get('maximum', '')}")
    if "minLength" in schema:
        notes.append(f"min {schema['minLength']} chars")
    if "maxLength" in schema:
        notes.append(f"max {schema['maxLength']} chars")
    if "format" in schema:
        notes.append(f"format {schema['format']}")
    if t == "array":
        lo, hi = schema.get("minItems"), schema.get("maxItems")
        count = f"{lo}–{hi}" if lo is not None and hi is not None else (f"up to {hi}" if hi is not None else (f"at least {lo}" if lo is not None else "any number of"))
        items = schema.get("items")
        notes.append(f"{count} items" + (f" of {describe_items(items)}" if items else ""))
    return notes


def describe_items(items):
    t = items.get("type", "any")
    inner = shape(items)
    return t + (f" ({'; '.join(inner)})" if inner else "")


def param(tool, name, schema):
    t = schema.get("type")
    if t not in TYPES:
        raise SystemExit(f"{tool}.{name}: type {t!r} is not a protocol type")
    desc = schema.get("description") or MISSING_DESCRIPTIONS.get((tool, name))
    if not desc:
        raise SystemExit(f"{tool}.{name}: no description (add it to MISSING_DESCRIPTIONS)")
    notes = shape(schema)
    if notes:
        desc = f"{desc} [{'; '.join(notes)}]"
    return t, desc


def kstr(s):
    return json.dumps(s, ensure_ascii=False).replace("$", "\\$")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_IN
    tools = json.load(open(path, encoding="utf-8"))
    names = [t["name"] for t in tools]
    if sorted(names) != sorted(MAPPING):
        raise SystemExit(f"mika tools changed: extra {sorted(set(names) - set(MAPPING))}, missing {sorted(set(MAPPING) - set(names))}")
    out = []
    w = out.append
    w("// GERADO por gadgetlink/tools/gen_phone_catalog.py a partir de src/test/resources/mika_tools.json")
    w("// (o tools/list real do servidor mika). Não editar à mão: rodar o gerador; o CommandCatalogTest")
    w("// confere este arquivo contra o golden. Os 29 comandos são STUBS da medição da Etapa 1: anunciados só")
    w("// com includeStubs e sem nenhum efeito — todo handler devolve not_implemented.")
    w("package com.darlisonfarich.gadgetlink.commands")
    w("")
    w("object PhoneCatalog {")
    w('    val TREE_DETAIL = ParamSpec(ParamType.STRING, "elements (default) or full (includes the window tree).")')
    w("")
    w("    /** Comando gadget -> ferramenta mika correspondente (1:1). */")
    w("    val MIKA: Map<String, String> = linkedMapOf(")
    for t in tools:
        w(f"        {kstr(MAPPING[t['name']][0])} to {kstr(t['name'])},")
    w("    )")
    w("")
    w("    fun stubs(): List<CommandSpec> = listOf(")
    for t in tools:
        mika = t["name"]
        gadget, timeout = MAPPING[mika]
        schema = t["inputSchema"]
        props = schema.get("properties", {})
        req = schema.get("required", [])
        writes = not t["annotations"]["readOnlyHint"]
        w("        CommandSpec.stub(")
        w(f"            name = {kstr(gadget)},")
        w(f"            description = {kstr(t['description'])},")
        for label, keys in (("required", [k for k in props if k in req]), ("optional", [k for k in props if k not in req])):
            if mika == "phone_observe" and label == "optional" and "detail" not in keys:
                keys.append("detail")
            if not keys:
                w(f"            {label} = emptyMap(),")
                continue
            w(f"            {label} = linkedMapOf(")
            for k in keys:
                if mika == "phone_observe" and k == "detail":
                    w('                "detail" to TREE_DETAIL,')
                    continue
                ty, desc = param(mika, k, props[k])
                w(f"                {kstr(k)} to ParamSpec(ParamType.{ty.upper()}, {kstr(desc)}),")
            w("            ),")
        w(f"            timeoutMs = {timeout:_}L,")
        w(f"            writes = {'true' if writes else 'false'},")
        w("        ),")
    w("    )")
    w("}")
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
