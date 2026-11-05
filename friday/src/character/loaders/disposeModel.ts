import * as THREE from 'three';

/** Release each owned resource once, including outline materials and skeleton textures. */
export function disposeModelTree(root: THREE.Object3D): void {
  const geometries = new Set<THREE.BufferGeometry>();
  const materials = new Set<THREE.Material>();
  const textures = new Set<THREE.Texture>();
  const skeletons = new Set<THREE.Skeleton>();
  root.traverse(object => {
    const mesh = object as THREE.Mesh;
    if (mesh.geometry) geometries.add(mesh.geometry);
    if (mesh.material) for (const material of Array.isArray(mesh.material) ? mesh.material : [mesh.material]) materials.add(material);
    if ((object as THREE.SkinnedMesh).skeleton) skeletons.add((object as THREE.SkinnedMesh).skeleton);
  });
  for (const material of materials) {
    for (const value of Object.values(material)) if (value instanceof THREE.Texture) textures.add(value);
    // The sphere map belongs to the model. The shared studio environment does not.
    const sphere = material.userData.animeUniforms?.uSphereMap?.value;
    if (sphere instanceof THREE.Texture) textures.add(sphere);
    material.dispose();
  }
  for (const texture of textures) texture.dispose();
  for (const geometry of geometries) geometry.dispose();
  for (const skeleton of skeletons) skeleton.dispose();
}
