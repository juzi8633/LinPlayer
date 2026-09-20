import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

// README 的正本在各插件自己的仓库,CI 抓下来写成静态副本落进 content/readme/<语言>/<slug>.md
// (SPEC 15.4 D481)。这里只负责渲染,抓不到就退回详情页的「概览」区块。
const readme = defineCollection({
  loader: glob({ base: 'src/content/readme', pattern: '**/*.md' }),
  schema: z.object({ title: z.string().optional() }),
});

const docs = defineCollection({
  loader: glob({ base: 'src/content/docs', pattern: '**/*.md' }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    order: z.number().default(50),
  }),
});

export const collections = { readme, docs };
