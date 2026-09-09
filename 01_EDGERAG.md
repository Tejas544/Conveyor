# 01 — EdgeRAG
### On-Device Multimodal RAG under a Memory Budget

**Flagship project.** Build first. **Aug 8–18 (11 days, ~45 hrs).**
Category: mentor's #1 (Scalable & Efficient Multimodal RAG) + #2 (Optimization & Quantization).
Risk: **Medium.** Prerequisite: file `00_FOUNDATIONS.md`.

---

## 1. Thesis

> Run a complete multimodal RAG pipeline — image + text corpus → retrieval → VLM generation —
> **entirely within a fixed memory budget (target ≤ 4 GB)**, using a quantized vision-language model.

The constraint *is* the project. Anyone can wire up RAG. Almost nobody builds RAG that fits on a
phone. Every design decision flows from the memory budget, which is exactly what makes it
defensible in an interview — there's a reason behind every choice.

**Why it's your flagship:** it is verbatim Samsung Research's stated direction (on-device AI,
lightweight transformers, compression of large transformer models, RAG, efficient serving), and
simultaneously what AWS Bedrock and Google Vertex AI shipped for multimodal retrieval this year.
It also sits directly downstream of the QAT work already on your CV.

---

## 2. Hard Rules

**No LangChain. No LlamaIndex. No Pinecone. No `model.generate()`.**

The instant your README says "built with LangChain," you become one of ten thousand identical CVs
in the most keyword-saturated category on the 2026 market. The entire value of this project is
that you wrote the parts everyone else imports. You may use HuggingFace `transformers` to *load
weights and tokenizers* — nothing else.

---

## 3. Technology Stack & Required Depth

| Technology | Depth | Notes |
|---|---|---|
| Python 3.10+ | **L3** | You have this |
| PyTorch — tensors, `nn.Module`, indexing, `.cuda()`, dtypes | **L3** | You have most of this |
| PyTorch — memory allocator, `max_memory_allocated`, streams | **L2** | Needed for the memory budget accounting |
| **Transformer internals** — attention, RoPE, RMSNorm, GQA/MQA | **L3** | Front-load. Everything sits on this |
| **KV cache** — what's cached, why, memory math | **L3** | Core of the project |
| **Paged KV cache** — block allocator, block tables, copy-on-write | **L3** | Your hardest, most impressive component |
| **Continuous batching** — prefill/decode split, scheduler, admission control | **L3** | Second-hardest component |
| **VLM architecture** — ViT/SigLIP encoder, projector, token interleaving | **L3** | The multimodal half |
| **Visual token pruning/merging** (ToMe, FastV) | **L2→L3** | Your novel contribution — L3 on whichever you implement |
| Weight-only quantization INT8/INT4, group-wise scales | **L2** | L3 comes later in QuantKit |
| Embedding models — SigLIP/CLIP (image), sentence-transformers (text) | **L2** | You use these, don't build them |
| HuggingFace `transformers` | **L2** | Loading weights, tokenizers, config introspection |
| FastAPI + `asyncio` | **L2** | Know the event loop, why blocking calls kill it |
| Evaluation — recall@k, latency percentiles, perplexity | **L3** | See Foundations §4 |
| matplotlib | **L1** | Plots for the README |

---

## 4. Learning Plan — What to Read, When, From Where

### Front-load: Aug 8, ~6 hours
**Transformer internals + KV cache.** Non-negotiable, everything depends on it.

1. Karpathy, **"Let's build GPT: from scratch, in code, spelled out"** (YouTube, 2h) — watch and
   code along. If you've seen it, skim at 1.5×.
2. Karpathy, **nanoGPT** repo — read `model.py` end to end. ~300 lines. This is your mental model.
3. Jay Alammar, **"The Illustrated Transformer"** — 30 min, fixes any remaining intuition gaps.
4. **KV cache math**, do this by hand: for a model with `L` layers, `H` kv-heads, head dim `d`,
   seq len `S`, batch `B`, in fp16 — KV cache bytes = `2 × B × L × H × d × S × 2`. Compute it for
   your chosen model at S=2048. **Memorise the result.** This number is the reason the whole
   project exists, and it's a standard interview question.

### Just-in-time, in build order

| When | Topic | Resource | Hours |
|---|---|---|---|
| Day 3 | **PagedAttention** | vLLM paper — Kwon et al., *"Efficient Memory Management for LLM Serving with PagedAttention"*, SOSP 2023 ([arXiv:2309.06180](https://arxiv.org/abs/2309.06180)). Read §3–4 closely. Then the vLLM blog *"Anatomy of vLLM"* | 3 |
| Day 5 | **Continuous batching** | Orca — Yu et al., OSDI 2022 (iteration-level scheduling). Plus Anyscale's *"Continuous batching for LLM inference"* blog | 2 |
| Day 6 | **VLM architecture** | LLaVA paper ([arXiv:2304.08485](https://arxiv.org/abs/2304.08485)) for the connector pattern; SigLIP ([arXiv:2303.15343](https://arxiv.org/abs/2303.15343)); HuggingFace **SmolVLM** blog post for a small concrete model | 3 |
| Day 7 | **Visual token reduction** | **ToMe** — Bolya et al., *"Token Merging: Your ViT But Faster"* ([arXiv:2210.09461](https://arxiv.org/abs/2210.09461)). **FastV** — *"An Image is Worth 1/2 Tokens After Layer 2"* ([arXiv:2403.06764](https://arxiv.org/abs/2403.06764)) | 3 |
| Day 8 | **Weight-only quantization** | Umar Jamil's quantization video (YouTube); HF `quanto` docs for the concept map. Depth comes in QuantKit | 2 |
| Throughout | **Memory-bound vs compute-bound** | Horace He, **"Making Deep Learning Go Brrrr From First Principles"** — [horace.io/brrr_intro.html](https://horace.io/brrr_intro.html). **Read this twice.** It is the single best thing written on why inference optimisation works | 1.5 |

---

## 5. Build Plan — Day by Day

Each phase is independently shippable. Commit at the end of every day.

### Days 1–2 · Baseline + harness
- Pick the model: **SmolVLM-2B** (recommended — small, well-documented, HF-supported),
  Qwen2-VL-2B, or Moondream2.
- Load weights via `transformers`. Write the **forward pass yourself** — no `generate()`.
- Assemble a small multimodal corpus: 200–1000 documents containing text + figures + tables.
  (Sources: arXiv paper pages, Wikipedia with images, or DocVQA / InfographicVQA datasets.)
- **Write `bench.py` before anything else** (Foundations §4): tokens/sec, TTFT p50/p99,
  peak memory, end-to-end query latency. Emits JSON + markdown table.
- Record the naive baseline. Every later number is measured against this.

**Deliverable:** working naive pipeline + baseline numbers committed.

### Day 3 · KV cache
- Naive contiguous KV cache. Measure decode speedup. *(Expect 2–3×.)*
- Plot: tokens/sec vs. sequence length, with and without cache.

### Days 4–6 · Paged KV cache ← the centrepiece
- Fixed-size blocks (start at 16 tokens/block).
- **Block allocator**: free list, allocate/free, fragmentation tracking.
- **Per-sequence block tables** mapping logical → physical blocks.
- **Copy-on-write prefix sharing** — critical for RAG, where many queries share a long retrieved
  context prefix. This is a genuinely strong result: measure the memory saved when 20 queries
  share one retrieved document.
- **Preemption policy** when the pool is exhausted: recompute vs. swap vs. reject. Pick one,
  justify it.
- Measure: max concurrent sequences before OOM, before vs. after. *(Expect 4–8×.)*

**This is where an interviewer will spend 15 minutes. Know it cold.**

### Day 7 · Visual token compression ← your differentiator
The real problem: **a single image costs 500–2000 tokens.** In a RAG pipeline retrieving 5 images,
the KV cache is dominated by visual tokens. Nobody's tutorial addresses this.
- Implement token merging (ToMe-style bipartite soft matching) or FastV-style attention-based
  pruning on visual tokens.
- **Sweep the pruning ratio** and plot the quality-vs-memory curve. The curve is the deliverable.
- Report: % visual tokens removed vs. % answer-quality retained.

### Day 8 · Continuous batching
- Separate prefill and decode queues. Admit new requests every decode step rather than waiting
  for the batch to drain.
- Admission control under memory pressure.
- Measure throughput under a Poisson arrival load. *(Expect 5–11×.)*
- Plot: throughput and p99 TTFT vs. concurrency — **show the tradeoff, don't hide it.**

### Day 9 · Quantization
- INT8, then INT4 weight-only, group-wise (group size 128).
- Measure: memory, tokens/sec, and quality delta on your held-out QA set.
- **Compare the VLM quality cliff against text-only LLM behaviour.** Vision encoders are more
  quantization-sensitive than decoders — if you find and document this, it's a real finding and a
  standout interview moment.

### Day 10 · Retrieval integration
- Multimodal embedding: SigLIP for images, a sentence-transformer for text.
- Simple flat index for now — **VecCore replaces this in project 02**, and that swap is the
  storyline that connects your two projects.
- Hybrid scoring across modalities; measure recall@k.

### Day 11 · Serve, measure, document
- FastAPI, async, OpenAI-compatible `/v1/chat/completions`.
- Full memory-budget accounting table: model weights / KV cache / vision encoder / embeddings /
  index — must sum under 4 GB.
- README: architecture diagram, ablation table (every component on/off), 3 plots, limitations.

---

## 6. Metrics You Must Report

| Metric | Baseline | Target |
|---|---|---|
| Peak memory (total pipeline) | naive fp16 | **≤ 4 GB** |
| Max concurrent sequences | naive KV cache | 4–8× |
| Decode throughput (tok/s) | HF `generate()` | 5–11× |
| TTFT p50 / p99 | HF `generate()` | report honestly, incl. regressions |
| Visual tokens removed | 0% | 50–75% at ≥95% quality |
| Retrieval recall@5 | flat exhaustive | within 2% |
| Answer quality | fp16, no pruning | ≥95% retention |

**Report regressions too.** Continuous batching improves throughput and *hurts* p99 TTFT.
Saying so unprompted is a strong maturity signal; hiding it and being caught is fatal.

---

## 7. Interview Defense — Answer These Cold

**Memory & paging**
1. Compute the KV cache size for your model at seq_len 2048, batch 8. Show the arithmetic.
2. Why fixed-size blocks and not a slab allocator or buddy system?
3. Block size 16 vs 64 — what's the tradeoff? (Internal fragmentation vs. block-table overhead.)
4. Pool is empty and a sequence needs a block. What do you do, and why that over the alternatives?
5. How does copy-on-write prefix sharing work, and why does RAG benefit disproportionately?

**Scheduling**
6. Why does continuous batching raise throughput but hurt p99 TTFT?
7. What's your admission-control policy? What happens under sustained overload?
8. Prefill and decode have different bottlenecks — name them. (Compute-bound vs. memory-bound.)

**Multimodal**
9. Why do visual tokens dominate the KV cache? Give the numbers.
10. Explain your token-merging algorithm. What information is lost?
11. Where's the quality cliff on your pruning curve, and why there?

**Quantization**
12. INT8 vs INT4 — where does a VLM break down versus a text-only LLM? Why the difference?
13. Why weight-only rather than quantizing activations too?
14. Why does INT4 help when decode is memory-bound? *(Fewer bytes over HBM — see Foundations §3.)*

**Scale**
15. What breaks first at 10M documents instead of 10K?
16. Where would you run this in production, and what changes?

---

## 8. Target CV Bullet

> Built an on-device multimodal RAG system serving an **INT4-quantized 2B VLM in under __ GB**,
> with a from-scratch paged KV-cache allocator and continuous-batching scheduler; cut visual-token
> KV cost **__%** via token merging at **__%** quality retention, reaching **__× decode throughput**
> and **__ ms p99** end-to-end at **recall@5 of __**.

---

## 9. Deliverables Checklist

- [ ] GitHub repo, daily commits, `BUGS.md` maintained from day 1
- [ ] `bench.py` harness emitting JSON + markdown
- [ ] Ablation table: every component independently on/off
- [ ] 3 plots: throughput vs. concurrency · memory vs. sequences · quality vs. pruning ratio
- [ ] Architecture diagram
- [ ] Memory-budget accounting table summing under 4 GB
- [ ] README with design decisions + known limitations
- [ ] FastAPI endpoint + a 60-second demo GIF

---

## 10. Risks & Cut Lines

| Risk | Mitigation |
|---|---|
| VLM loading/preprocessing eats 2 days | Start with **SmolVLM** — best-documented small VLM. Fall back to text-only Qwen2.5-0.5B and add vision later |
| Paged KV cache has subtle correctness bugs | Test against the naive cache: identical logits required. Write that test **before** the optimisation |
| Colab disconnects mid-benchmark | Incremental JSON writes; checkpoint to Drive (Foundations §3) |
| Token merging degrades quality badly | That's still a result. Publish the curve and explain the failure mode honestly |
| Behind schedule at Day 8 | **Cut:** continuous batching, then the FastAPI layer. **Never cut:** paged KV cache or the benchmark harness — they're the project |
