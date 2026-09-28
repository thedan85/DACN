"use client";

import Image from "next/image";
import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { ArrowRight, ChevronDown, Heart, Menu, Search, ShoppingBag, Sparkles, X } from "lucide-react";
import { Reveal } from "./components/Reveal";
import { CountUp } from "./components/CountUp";
import { Bubbles } from "./components/Bubbles";

const categories = [
  { title: "Livestock", detail: "Fish, shrimp, snails & plants — quarantined and healthy", count: "48 items", image: "/images/category-livestock.png" },
  { title: "Consumables", detail: "Food, fertilisers & medications for daily care", count: "32 items", image: "/images/category-consumables.png" },
  { title: "Equipment", detail: "Filters, lights, tanks & tools for every setup", count: "56 items", image: "/images/category-equipment.png" },
];

const products = [
  { name: "Red neon blue eye", category: "Livestock", price: "$4.99", image: "/images/product-neon.png" },
  { name: "Cherry Shrimp", category: "Livestock", price: "$6.49", image: "/images/product-shrimp.png" },
  { name: "Anubias Nana", category: "Livestock", price: "$9.99", image: "/images/product-anubias.png" },
  { name: "Pleco L183", category: "Livestock", price: "$12.99", image: "/images/product-pleco.png" },
];

const heroEntrance = (index: number) => ({
  initial: { opacity: 0, y: 24 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.5, delay: index * 0.08, ease: [0.22, 1, 0.36, 1] as const },
});

export default function Home() {
  const [cartCount, setCartCount] = useState(0);
  const [searchOpen, setSearchOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [subscribed, setSubscribed] = useState(false);
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  function handleSubscribe(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (email.trim()) setSubscribed(true);
  }

  return (
    <main>
      <header className={scrolled ? "site-header is-scrolled" : "site-header"}>
        <div className="header-inner">
          <a className="brand" href="#top" aria-label="Aquarium Store home">Aquarium Store</a>
          <nav className={menuOpen ? "main-nav is-open" : "main-nav"} aria-label="Main navigation">
            <a href="#shop">Shop</a>
            <a href="#shop">Fish</a>
            <a href="#shop">Plants</a>
            <a href="#shop">Equipment</a>
            <a href="#shop">Consumables</a>
          </nav>
          <div className="header-actions">
            <button className="icon-button" onClick={() => setSearchOpen(!searchOpen)} aria-label="Search"><Search size={19} /></button>
            <a className="auth-link" href="#newsletter">Log In</a>
            <a className="signup-link" href="#newsletter">Sign Up</a>
            <button className="icon-button cart-button" onClick={() => setCartCount(cartCount + 1)} aria-label="Shopping cart"><ShoppingBag size={19} />{cartCount > 0 && <span key={cartCount}>{cartCount}</span>}</button>
            <button className="menu-button" onClick={() => setMenuOpen(!menuOpen)} aria-label="Toggle menu">{menuOpen ? <X size={22} /> : <Menu size={22} />}</button>
          </div>
        </div>
        {searchOpen && <div className="search-panel"><Search size={18} /><input autoFocus placeholder="Search livestock, plants, equipment..." /><button onClick={() => setSearchOpen(false)} aria-label="Close search"><X size={18} /></button></div>}
      </header>

      <section id="top" className="hero-section">
        <Image className="hero-image" src="/images/hero-aquarium.png" alt="A lush planted aquarium" fill priority sizes="100vw" />
        <div className="hero-overlay" />
        <Bubbles />
        <div className="hero-rays" aria-hidden="true" />
        <div className="container hero-content">
          <motion.p className="eyebrow aqua" {...heroEntrance(0)}>Premium aquarium supplies</motion.p>
          <motion.h1 {...heroEntrance(1)}>Everything your<br /><em>underwater world</em><br />needs.</motion.h1>
          <motion.p className="hero-copy" {...heroEntrance(2)}>From healthy livestock to premium equipment — we help aquascapers and hobbyists build thriving aquatic ecosystems.</motion.p>
          <motion.div className="hero-actions" {...heroEntrance(3)}><a className="button button-primary" href="#featured">Shop now <ArrowRight size={17} /></a><a className="button button-ghost" href="#community">Join the community</a></motion.div>
        </div>
        <div className="scroll-cue"><span /> Scroll to explore</div>
      </section>

      <section id="shop" className="section categories-section">
        <div className="container">
          <Reveal><SectionHeading eyebrow="Categories" title="Shop by category" /></Reveal>
          <div className="category-grid">{categories.map((category, i) => <Reveal key={category.title} delay={i * 0.08}><a className="category-card" href="#featured"><Image src={category.image} alt="" fill sizes="(max-width: 700px) 100vw, 33vw" /><div className="card-shade" /><div className="category-copy"><h3>{category.title}</h3><p>{category.detail}</p><span>{category.count} <ArrowRight size={16} /></span></div></a></Reveal>)}</div>
        </div>
      </section>

      <section id="featured" className="section featured-section">
        <div className="container"><Reveal><SectionHeading eyebrow="Featured" title="Popular products" subtitle="Handpicked favorites our community loves" /></Reveal><div className="product-grid">{products.map((product, i) => <Reveal key={product.name} delay={i * 0.08}><article className="product-card"><div className="product-image"><Image src={product.image} alt={product.name} fill sizes="(max-width: 700px) 100vw, 25vw" /></div><div className="product-info"><span className="tag">{product.category}</span><h3>{product.name}</h3><div className="product-bottom"><strong>{product.price}</strong><motion.button className="add-button" whileTap={{ scale: 0.92 }} onClick={() => setCartCount(cartCount + 1)} aria-label={`Add ${product.name} to cart`}><ShoppingBag size={17} /></motion.button></div></div></article></Reveal>)}</div><a className="text-link" href="#shop">Browse all products <ArrowRight size={16} /></a></div>
      </section>

      <section id="about" className="section values-section"><div className="container"><Reveal><SectionHeading eyebrow="Why choose us" title="Built for hobbyists, by hobbyists" /></Reveal><div className="value-grid"><Reveal delay={0}><Value icon="✦" title="Quarantined Livestock" detail="Every fish, shrimp, and plant goes through a 14-day quarantine before being listed." /></Reveal><Reveal delay={0.08}><Value icon="◌" title="Safe Delivery Guarantee" detail="Climate-controlled packaging with a live-arrival guarantee on all livestock orders." /></Reveal><Reveal delay={0.16}><Value icon="⌁" title="Expert Support" detail="Our aquascaping team answers questions within 24 hours — even on weekends." /></Reveal><Reveal delay={0.24}><Value icon="✧" title="Active Community" detail="Join 12,000+ hobbyists sharing tips, tank journals, and aquascaping inspiration." /></Reveal></div></div></section>

      <section id="community" className="community-section"><div className="container community-layout"><Reveal><div><div className="community-icon">✦</div><p className="eyebrow aqua">Community</p><h2>Join 12,000+ aquascapers</h2><p>Share your tank journals, ask for advice, and get inspired by setups from beginners to award-winning scapers.</p><div className="hero-actions"><a className="button button-primary" href="#newsletter">Browse Forum</a><a className="button button-ghost" href="#newsletter">Join Free</a></div></div></Reveal><div className="metric-grid"><div><strong><CountUp value={12} suffix="K+" /></strong><span>Members</span></div><div><strong><CountUp value={3.2} decimals={1} suffix="K" /></strong><span>Tank Journals</span></div><div><strong><CountUp value={850} suffix="+" /></strong><span>Species Guides</span></div><div><strong><CountUp value={98} suffix="%" /></strong><span>Positive Feedback</span></div></div></div></section>

      <section id="newsletter" className="newsletter-section"><div className="newsletter-inner"><Reveal><SectionHeading eyebrow="Newsletter" title="Stay in the loop" subtitle="Get weekly care tips, new arrival alerts, and exclusive offers straight to your inbox." /></Reveal><Reveal delay={0.08}>{subscribed ? <div className="success-message"><Sparkles size={20} /> You are on the list. See you in the next dispatch.</div> : <form onSubmit={handleSubscribe} className="newsletter-form"><div><input id="email" type="email" required value={email} onChange={(event) => setEmail(event.target.value)} placeholder="Enter your email" /><button className="button button-dark" type="submit">Subscribe</button></div></form>}</Reveal></div></section>

      <footer className="site-footer"><div className="container"><Reveal><div className="footer-grid"><FooterColumn title="Shop" links={["All products", "Fish", "Plants", "Equipment"]} /><FooterColumn title="Company" links={["About us", "Careers", "Contact", "Blog"]} /><FooterColumn title="Community" links={["Forum", "Gallery", "Events", "Support"]} /><FooterColumn title="Legal" links={["Privacy policy", "Terms of service", "Returns"]} /></div></Reveal><div className="footer-bottom"><span>© 2026 Naturaquarium. All rights reserved.</span><div className="socials"><a href="#top" aria-label="Instagram">ig</a><a href="#top" aria-label="Twitter">tw</a><a href="#top" aria-label="YouTube">yt</a></div></div></div></footer>
    </main>
  );
}

function SectionHeading({ eyebrow, title, subtitle }: { eyebrow: string; title: string; subtitle?: string }) { return <div className="section-heading"><p className="eyebrow">{eyebrow}</p><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>; }
function Value({ icon, title, detail }: { icon: string; title: string; detail: string }) { return <div className="value-item"><span className="value-icon">{icon}</span><div><h3>{title}</h3><p>{detail}</p></div></div>; }
function FooterColumn({ title, links }: { title: string; links: string[] }) { return <div><p className="footer-title">{title}</p>{links.map((link) => <a href="#shop" key={link}>{link}</a>)}</div>; }
