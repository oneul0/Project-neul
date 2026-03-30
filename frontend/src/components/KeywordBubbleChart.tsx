"use client";

import { useEffect, useRef } from "react";
import * as d3 from "d3";

interface KeywordNode extends d3.SimulationNodeDatum {
    id: string;
    value: number;
    radius: number;
    color: string;
}

export default function KeywordBubbleChart({ data }: { data: Record<string, number> }) {
    const svgRef = useRef<SVGSVGElement>(null);
    const simulationRef = useRef<d3.Simulation<KeywordNode, undefined> | null>(null);

    useEffect(() => {
        if (!svgRef.current) return;

        const width = 600;
        const height = 400;
        const svg = d3.select(svgRef.current);
        
        // Initial setup
        if (svg.select("g.container").empty()) {
            svg.append("g").attr("class", "container");
        }
        const container = svg.select("g.container");

        // 1. Data Capping (Top 15 for UI Tidiness)
        const sortedKeywords = Object.entries(data)
            .sort((a, b) => b[1] - a[1])
            .slice(0, 15);

        const keywords = sortedKeywords.map(([name, value]) => ({
            id: name,
            value: Number(value),
        }));

        if (keywords.length === 0) {
            container.selectAll("*").remove();
            return;
        }

        // 2. Refined Scaling (Balanced sizes)
        const maxVal = d3.max(keywords, d => d.value) || 1;
        const radiusScale = d3.scaleSqrt()
            .domain([0, maxVal])
            .range([25, 65]);

        const colorScale = d3.scaleSequential(d3.interpolateSpectral)
            .domain([0, keywords.length]);

        // Map to nodes (Preserve existing positions)
        const existingNodes = simulationRef.current ? simulationRef.current.nodes() : [];
        const nodeMap = new Map(existingNodes.map(n => [n.id, n]));

        const nodes: KeywordNode[] = keywords.map((k, i) => {
            const existing = nodeMap.get(k.id);
            return {
                ...k,
                radius: radiusScale(k.value),
                color: colorScale(i),
                x: existing?.x ?? (width / 2 + (Math.random() - 0.5) * 100),
                y: existing?.y ?? (height / 2 + (Math.random() - 0.5) * 100),
                vx: existing?.vx ?? 0,
                vy: existing?.vy ?? 0
            };
        });

        // 3. Force Simulation (Firmer Collisions)
        if (simulationRef.current) simulationRef.current.stop();

        const simulation = d3.forceSimulation<KeywordNode>(nodes)
            .velocityDecay(0.12)
            .force("x", d3.forceX(width / 2).strength(0.008))
            .force("y", d3.forceY(height / 2).strength(0.008))
            .force("collide", d3.forceCollide<KeywordNode>().radius(d => d.radius + 6).iterations(5))
            .force("charge", d3.forceManyBody().strength(3))
            .alphaTarget(0.1)
            .on("tick", () => {
                const time = Date.now() / 1500;
                
                nodes.forEach((d, i) => {
                    // Buoyant Bobbing
                    d.vy! += Math.sin(time + i) * 0.04;
                    d.vx! += Math.cos(time * 0.5 + i) * 0.01;

                    // 4. Strict Boundary Locking
                    d.x = Math.max(d.radius, Math.min(width - d.radius, d.x!));
                    d.y = Math.max(d.radius, Math.min(height - d.radius, d.y!));
                });

                const bubbles = container.selectAll<SVGGElement, KeywordNode>("g.bubble")
                    .data(nodes, d => d.id);

                const bubblesEnter = bubbles.enter()
                    .append("g")
                    .attr("class", "bubble")
                    .style("cursor", "pointer");

                bubblesEnter.append("circle")
                    .attr("r", 0)
                    .attr("fill", d => d.color)
                    .attr("fill-opacity", 0.35)
                    .attr("stroke", d => d.color)
                    .attr("stroke-width", 1.5)
                    .transition().duration(1000).ease(d3.easeElasticOut)
                    .attr("r", d => d.radius);

                bubblesEnter.append("text")
                    .attr("text-anchor", "middle")
                    .attr("dy", ".3em")
                    .attr("fill", "white")
                    .style("font-size", "0px")
                    .style("font-weight", "900")
                    .style("pointer-events", "none")
                    .text(d => d.id)
                    .transition().duration(1000)
                    .style("font-size", d => Math.min(d.radius / 2.8, 11) + "px");

                const bubblesUpdate = bubbles.merge(bubblesEnter);
                
                bubblesUpdate.attr("transform", d => `translate(${d.x},${d.y})`);
                
                bubblesUpdate.select("circle")
                    .attr("r", d => d.radius);

                bubbles.exit().remove();
            });

        simulationRef.current = simulation;

        return () => {
            simulation.stop();
        };
    }, [data]);

    return (
        <div className="w-full h-full flex items-center justify-center bg-slate-900/10 rounded-3xl overflow-hidden border border-slate-800/50 relative">
            <svg 
                ref={svgRef} 
                width="100%" 
                height="100%" 
                viewBox="0 0 600 400"
                preserveAspectRatio="xMidYMid meet"
                className="drop-shadow-2xl"
            />
            {Object.keys(data).length === 0 && (
                <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
                    <p className="text-slate-500 font-bold uppercase tracking-widest text-xs">Waiting for keywords...</p>
                </div>
            )}
        </div>
    );
}
