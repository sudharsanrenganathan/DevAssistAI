/* ============================================================================
 * DASHBOARD CORE NODE — "ALPHA" VERSION (saved 2026-07-13)
 *
 * This is the ORIGINAL DevAssist Core visual: a photorealistic glass TorusKnot
 * with an inner glowing sphere, rotating steadily. Saved so it can be restored
 * on request ("go back to alpha" / "restore the alpha core"). To revert, paste
 * this block back over the THREE.JS 3D CORE section in BOTH:
 *   - frontend/dashboard.html
 *   - backend/devassist-backend/src/main/resources/static/dashboard.html
 * The #core-3d-container CSS and the <div id="core-3d-container"></div> markup
 * are unchanged between versions — only this script block differs.
 * ============================================================================ */

// --- THREE.JS 3D CORE (High-Fidelity Glass Knot) ---
let scene, camera, renderer, globe, innerGlobe;
const container = document.getElementById('core-3d-container');
function init3DCore() {
    if (!container) return;
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(75, container.clientWidth / container.clientHeight, 0.1, 1000);

    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    const width = container.clientWidth || 200;
    const height = container.clientHeight || 150;
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    container.appendChild(renderer.domElement);

    // Complex organic geometry (Organic Glass Swirl) - scaled for small card
    const geometry = new THREE.TorusKnotGeometry(1.2, 0.45, 150, 24, 2, 3);

    // Photorealistic Glass Material with Attenuation and Iridescence
    const material = new THREE.MeshPhysicalMaterial({
        color: 0xffffff,
        metalness: 0.1,
        roughness: 0,
        transmission: 1.0,
        thickness: 4.0,
        ior: 1.5,
        iridescence: 1.0,
        iridescenceIOR: 1.7,
        transparent: true,
        opacity: 1,
        clearcoat: 1,
        clearcoatRoughness: 0.05,
        attenuationColor: 0x7c3aed, // Purple internal depth
        attenuationDistance: 2.0
    });

    globe = new THREE.Mesh(geometry, material);
    scene.add(globe);

    // Inner Glowing Core (The "Neural Sun") - scaled smaller
    const innerGeom = new THREE.SphereGeometry(0.6, 32, 32);
    const innerMat = new THREE.MeshBasicMaterial({
        color: 0x7c3aed,
        transparent: true,
        opacity: 0.8
    });
    innerGlobe = new THREE.Mesh(innerGeom, innerMat);
    scene.add(innerGlobe);

    // Neural Lighting (Rim/Spot effects)
    const p1 = new THREE.PointLight(0x06f5cd, 50, 15); p1.position.set(5, 5, 8); scene.add(p1);
    const p2 = new THREE.PointLight(0x7c3aed, 60, 15); p2.position.set(-5, -5, 8); scene.add(p2);
    const p3 = new THREE.PointLight(0x3b82f6, 40, 15); p3.position.set(0, 0, -8); scene.add(p3);

    scene.add(new THREE.AmbientLight(0x404040, 0.5));

    camera.position.z = 3.5;
    animate3D();
}

setTimeout(init3DCore, 300);

function animate3D() {
    requestAnimationFrame(animate3D);
    const time = Date.now() * 0.001;

    if (globe) {
        globe.rotation.y += 0.006;
        globe.rotation.x += 0.003;
        globe.rotation.z += 0.002;
        const scale = 1 + Math.sin(time * 0.7) * 0.04;
        globe.scale.set(scale, scale, scale);
    }

    if (innerGlobe) {
        const s2 = 1 + Math.cos(time * 0.7) * 0.1;
        innerGlobe.scale.set(s2, s2, s2);
        innerGlobe.material.opacity = 0.5 + Math.sin(time * 1.2) * 0.3;
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
