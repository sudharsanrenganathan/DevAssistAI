/* ============================================================================
 * DASHBOARD CORE NODE — "ALPHA 3" VERSION (saved 2026-07-13)
 *
 * The "Neural Connectome + sparks": a rotating neural-network graph (42 nodes on
 * a Fibonacci sphere, teal near-links + purple mid-links pulsing out of phase,
 * glowing node dots, a bright pulsing core + purple glow) PLUS 55 spark particles
 * orbiting in tilted planes just OUTSIDE the graph (radius 1.75-3.2) so they read
 * as energy flying out. Respects "reduce motion" via the `calm` factor.
 *
 * Restore on request ("go back to alpha 3"): paste this block over the THREE.JS
 * 3D CORE section in BOTH:
 *   - frontend/dashboard.html
 *   - backend/devassist-backend/src/main/resources/static/dashboard.html
 * Version history: alpha = glass TorusKnot; alpha 2 = neural brain core
 * (shells+particles); alpha 3 = this connectome + sparks.
 * ============================================================================ */

// --- THREE.JS 3D CORE (Neural Connectome) ---
let scene, camera, renderer, brain, nodePts, linkNear, linkFar, coreSun, coreGlow, particles, particleData = [];
const container = document.getElementById('core-3d-container');
function init3DCore() {
    if (!container) return;
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(70, (container.clientWidth || 200) / (container.clientHeight || 150), 0.1, 1000);
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setSize(container.clientWidth || 200, container.clientHeight || 150);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    container.appendChild(renderer.domElement);

    brain = new THREE.Group();
    scene.add(brain);

    // Evenly distribute nodes on a sphere (Fibonacci lattice)
    const N = 42, R = 1.6;
    const nodes = [];
    const inc = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < N; i++) {
        const y = 1 - (i / (N - 1)) * 2;
        const rad = Math.sqrt(Math.max(0, 1 - y * y));
        const phi = i * inc;
        nodes.push(new THREE.Vector3(Math.cos(phi) * rad * R, y * R, Math.sin(phi) * rad * R));
    }

    // Node glowing dots
    const npos = new Float32Array(N * 3);
    nodes.forEach((v, i) => { npos[i * 3] = v.x; npos[i * 3 + 1] = v.y; npos[i * 3 + 2] = v.z; });
    const nGeo = new THREE.BufferGeometry();
    nGeo.setAttribute('position', new THREE.BufferAttribute(npos, 3));
    nodePts = new THREE.Points(nGeo, new THREE.PointsMaterial({
        color: 0xa5f3fc, size: 0.15, transparent: true, opacity: 0.95,
        blending: THREE.AdditiveBlending, depthWrite: false
    }));
    brain.add(nodePts);

    // Synapse links: near (teal) and mid-range (purple)
    const nearPos = [], farPos = [];
    for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) {
        const dst = nodes[i].distanceTo(nodes[j]);
        if (dst < 1.1) nearPos.push(nodes[i].x, nodes[i].y, nodes[i].z, nodes[j].x, nodes[j].y, nodes[j].z);
        else if (dst < 1.55) farPos.push(nodes[i].x, nodes[i].y, nodes[i].z, nodes[j].x, nodes[j].y, nodes[j].z);
    }
    const mkLinks = (arr, color, opacity) => {
        const g = new THREE.BufferGeometry();
        g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(arr), 3));
        const m = new THREE.LineBasicMaterial({ color, transparent: true, opacity, blending: THREE.AdditiveBlending, depthWrite: false });
        brain.add(new THREE.LineSegments(g, m));
        return m;
    };
    linkNear = mkLinks(nearPos, 0x06f5cd, 0.5);
    linkFar  = mkLinks(farPos, 0x7c3aed, 0.28);

    // Bright pulsing core + glow
    coreSun = new THREE.Mesh(new THREE.SphereGeometry(0.42, 24, 24),
        new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.9 }));
    scene.add(coreSun);
    coreGlow = new THREE.Mesh(new THREE.SphereGeometry(0.7, 24, 24),
        new THREE.MeshBasicMaterial({ color: 0x7c3aed, transparent: true, opacity: 0.16, blending: THREE.AdditiveBlending, depthWrite: false }));
    scene.add(coreGlow);

    // Emitted sparks — particles orbiting in tilted planes JUST OUTSIDE the
    // graph, so they read as energy flying out of the connectome
    const NP = 55;
    const ppos = new Float32Array(NP * 3);
    particleData = [];
    for (let i = 0; i < NP; i++) {
        particleData.push({
            r: 1.75 + Math.random() * 1.45,
            speed: 0.3 + Math.random() * 0.9,
            off: Math.random() * Math.PI * 2,
            tilt: Math.random() * Math.PI,
            yaw: Math.random() * Math.PI * 2
        });
    }
    const ppGeo = new THREE.BufferGeometry();
    ppGeo.setAttribute('position', new THREE.BufferAttribute(ppos, 3));
    particles = new THREE.Points(ppGeo, new THREE.PointsMaterial({
        color: 0x67e8f9, size: 0.08, transparent: true, opacity: 0.85,
        blending: THREE.AdditiveBlending, depthWrite: false
    }));
    scene.add(particles);

    camera.position.z = 4.2;
    animate3D();
}

setTimeout(init3DCore, 300);

function animate3D() {
    requestAnimationFrame(animate3D);
    const t = Date.now() * 0.001;
    const calm = document.body.classList.contains('reduce-motion') ? 0.35 : 1;

    if (brain) {
        brain.rotation.y += 0.005 * calm;
        brain.rotation.x = Math.sin(t * 0.25 * calm) * 0.25;
    }
    if (linkNear) linkNear.opacity = 0.35 + (Math.sin(t * 2.2) + 1) * 0.14;       // synapses firing
    if (linkFar)  linkFar.opacity  = 0.16 + (Math.sin(t * 1.5 + 1.5) + 1) * 0.10; // out of phase
    if (nodePts)  nodePts.material.size = 0.14 + Math.sin(t * 3) * 0.03;
    if (coreSun)  { const s = 1 + Math.sin(t * 2.6) * 0.14; coreSun.scale.set(s, s, s); }
    if (coreGlow) {
        const g = 1 + Math.sin(t * 2.6) * 0.2; coreGlow.scale.set(g, g, g);
        coreGlow.material.opacity = 0.12 + (Math.sin(t * 2.6) + 1) * 0.05;
    }
    if (particles) {
        const arr = particles.geometry.attributes.position.array;
        for (let i = 0; i < particleData.length; i++) {
            const d = particleData[i];
            const a = t * d.speed * calm + d.off;
            let x = Math.cos(a) * d.r, y = Math.sin(a) * d.r, z = 0;
            const y2 = y * Math.cos(d.tilt) - z * Math.sin(d.tilt);
            const z2 = y * Math.sin(d.tilt) + z * Math.cos(d.tilt);
            arr[i * 3]     = x * Math.cos(d.yaw) + z2 * Math.sin(d.yaw);
            arr[i * 3 + 1] = y2;
            arr[i * 3 + 2] = -x * Math.sin(d.yaw) + z2 * Math.cos(d.yaw);
        }
        particles.geometry.attributes.position.needsUpdate = true;
        particles.rotation.y += 0.001 * calm;
    }
    renderer.render(scene, camera);
}

window.addEventListener('resize', () => {
    if (renderer && container) {
        const w = container.clientWidth;
        const h = container.clientHeight;
        renderer.setSize(w, h);
        camera.aspect = w / h;
        camera.updateProjectionMatrix();
    }
});
