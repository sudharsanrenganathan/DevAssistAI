/* ============================================================================
 * DASHBOARD CORE NODE — "ALPHA 2" VERSION (saved 2026-07-13)
 *
 * The "Neural Brain Core": two counter-rotating wireframe icosahedron shells
 * (teal outer, purple inner), a pulsing white neural sun with an additive glow
 * halo, and 70 synapse particles orbiting in randomly tilted planes. Respects
 * the "reduce motion" preference via the `calm` factor.
 *
 * Restore on request ("go back to alpha 2"): paste this block over the
 * THREE.JS 3D CORE section in BOTH:
 *   - frontend/dashboard.html
 *   - backend/devassist-backend/src/main/resources/static/dashboard.html
 * Version history: alpha = glass TorusKnot (dashboard-core-alpha.js);
 *                  alpha 2 = this neural brain core.
 * ============================================================================ */

// --- THREE.JS 3D CORE (Neural Brain Core) ---
let scene, camera, renderer, shellA, shellB, coreSun, coreGlow, particles, particleData = [];
const container = document.getElementById('core-3d-container');
function init3DCore() {
    if (!container) return;
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(70, (container.clientWidth || 200) / (container.clientHeight || 150), 0.1, 1000);

    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setSize(container.clientWidth || 200, container.clientHeight || 150);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    container.appendChild(renderer.domElement);

    // Outer geodesic shell (teal), glowing wireframe
    shellA = new THREE.Mesh(
        new THREE.IcosahedronGeometry(1.65, 1),
        new THREE.MeshBasicMaterial({ color: 0x06f5cd, wireframe: true, transparent: true, opacity: 0.55 })
    );
    scene.add(shellA);

    // Inner geodesic shell (purple), counter-rotating
    shellB = new THREE.Mesh(
        new THREE.IcosahedronGeometry(1.15, 1),
        new THREE.MeshBasicMaterial({ color: 0x7c3aed, wireframe: true, transparent: true, opacity: 0.5 })
    );
    scene.add(shellB);

    // Neural sun — bright pulsing core
    coreSun = new THREE.Mesh(
        new THREE.SphereGeometry(0.5, 32, 32),
        new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.95 })
    );
    scene.add(coreSun);

    // Soft glow halo around the core
    coreGlow = new THREE.Mesh(
        new THREE.SphereGeometry(0.8, 32, 32),
        new THREE.MeshBasicMaterial({ color: 0x06f5cd, transparent: true, opacity: 0.18, blending: THREE.AdditiveBlending, depthWrite: false })
    );
    scene.add(coreGlow);

    // Synapse particle field — points orbiting in randomly tilted planes
    const N = 70;
    const pos = new Float32Array(N * 3);
    particleData = [];
    for (let i = 0; i < N; i++) {
        particleData.push({
            r: 1.2 + Math.random() * 1.15,
            speed: 0.25 + Math.random() * 0.85,
            off: Math.random() * Math.PI * 2,
            tilt: Math.random() * Math.PI,
            yaw: Math.random() * Math.PI * 2
        });
    }
    const pGeo = new THREE.BufferGeometry();
    pGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    particles = new THREE.Points(pGeo, new THREE.PointsMaterial({
        color: 0x67e8f9, size: 0.1, transparent: true, opacity: 0.9,
        blending: THREE.AdditiveBlending, depthWrite: false
    }));
    scene.add(particles);

    // Neural lighting
    const p1 = new THREE.PointLight(0x06f5cd, 40, 15); p1.position.set(5, 5, 8); scene.add(p1);
    const p2 = new THREE.PointLight(0x7c3aed, 50, 15); p2.position.set(-5, -5, 8); scene.add(p2);
    scene.add(new THREE.AmbientLight(0x404040, 0.6));

    camera.position.z = 4;
    animate3D();
}

setTimeout(init3DCore, 300);

function animate3D() {
    requestAnimationFrame(animate3D);
    const t = Date.now() * 0.001;
    const calm = document.body.classList.contains('reduce-motion') ? 0.35 : 1;

    if (shellA) { shellA.rotation.y += 0.004 * calm; shellA.rotation.x += 0.002 * calm; }
    if (shellB) { shellB.rotation.y -= 0.007 * calm; shellB.rotation.z += 0.003 * calm; }
    if (coreSun) { const s = 1 + Math.sin(t * 2.4) * 0.13; coreSun.scale.set(s, s, s); }
    if (coreGlow) {
        const g = 1 + Math.sin(t * 2.4) * 0.22; coreGlow.scale.set(g, g, g);
        coreGlow.material.opacity = 0.14 + (Math.sin(t * 2.4) + 1) * 0.06;
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
        particles.rotation.y += 0.0012 * calm;
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
