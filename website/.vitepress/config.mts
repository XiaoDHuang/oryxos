import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'OryxOS',
  titleTemplate: ':title — OryxOS',
  description: '企业能完全掌控的、Java 原生的、私有可审计的 Agent OS。',
  base: '/oryxos/',
  cleanUrls: true,
  appearance: 'force-light',

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/oryxos/logo-icon.svg' }],
    ['meta', { name: 'author', content: 'OryxOS' }],
    ['meta', { name: 'keywords', content: 'OryxOS, Agent OS, Java, Spring Boot, AI Agent, LLM, ReAct, MCP, enterprise AI, private deployment' }],
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'OryxOS' }],
    ['meta', { property: 'og:title', content: 'OryxOS — Enterprise Agent OS, Java-native & Auditable' }],
    ['meta', { property: 'og:description', content: '企业能完全掌控的、Java 原生的、私有可审计的 Agent OS。' }],
    ['meta', { property: 'og:url', content: 'https://XiaoDHuang.github.io/oryxos/' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'OryxOS — Enterprise Agent OS, Java-native & Auditable' }],
    ['meta', { name: 'twitter:description', content: '企业能完全掌控的、Java 原生的、私有可审计的 Agent OS。' }],
    ['link', { rel: 'canonical', href: 'https://XiaoDHuang.github.io/oryxos/' }],
  ],

  locales: {
    root: {
      label: '中文',
      lang: 'zh-CN',
      themeConfig: {
        nav: [
          { text: '首页', link: '/' },
          { text: '文档', link: '/docs/what' },
        ],
        sidebar: {
          '/docs/': [
            {
              text: '快速入门',
              items: [
                { text: 'OryxOS 是什么', link: '/docs/what' },
                { text: '核心能力', link: '/docs/features' },
                { text: '快速开始', link: '/docs/quick-start' },
              ],
            },
            {
              text: '设计',
              items: [
                { text: '业界调研', link: '/docs/industry-research' },
                { text: '需求分析', link: '/docs/demand-analysis' },
                { text: '技术方案', link: '/docs/technical-solution' },
                { text: 'AI 编程实施指南', link: '/docs/ai-programming-guide' },
              ],
            },
          ],
        },
      },
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      themeConfig: {
        nav: [
          { text: 'Home', link: '/en/' },
          { text: 'Docs', link: '/en/docs/what' },
        ],
        sidebar: {
          '/en/docs/': [
            {
              text: 'Getting Started',
              items: [
                { text: 'What is OryxOS', link: '/en/docs/what' },
                { text: 'Core Features', link: '/en/docs/features' },
                { text: 'Quick Start', link: '/en/docs/quick-start' },
              ],
            },
            {
              text: 'Design',
              items: [
                { text: 'Industry Research', link: '/en/docs/industry-research' },
                { text: 'Demand Analysis', link: '/en/docs/demand-analysis' },
                { text: 'Technical Solution', link: '/en/docs/technical-solution' },
                { text: 'AI Programming Guide', link: '/en/docs/ai-programming-guide' },
              ],
            },
          ],
        },
      },
    },
  },

  themeConfig: {
    siteTitle: false,
    logo: '/logo.svg',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/XiaoDHuang/oryxos' },
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © 2026 OryxOS Contributors',
    },
  },

  sitemap: {
    hostname: 'https://XiaoDHuang.github.io/oryxos',
  },
})
